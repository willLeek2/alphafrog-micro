package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared sync/async finance marker processor. It never returns marker JSON to model-facing callers. */
@Component
public class FinanceRecordChannelProcessor {

    private final FinanceRecordDecoder decoder;
    private final FinanceRecordSchemaValidator schemaValidator;
    private final FinanceEnvironmentVerifier environmentVerifier;
    private final FinanceMethodResolutionQuery resolutionQuery;
    private final FinanceRecordPersister persister;
    private final FinanceRecordChannelObservability observability;
    private final ObjectMapper objectMapper;

    public FinanceRecordChannelProcessor(
            FinanceRecordDecoder decoder,
            FinanceRecordSchemaValidator schemaValidator,
            FinanceEnvironmentVerifier environmentVerifier,
            FinanceMethodResolutionQuery resolutionQuery,
            FinanceRecordPersister persister,
            FinanceRecordChannelObservability observability,
            ObjectMapper objectMapper) {
        this.decoder = decoder;
        this.schemaValidator = schemaValidator;
        this.environmentVerifier = environmentVerifier;
        this.resolutionQuery = resolutionQuery;
        this.persister = persister;
        this.observability = observability;
        this.objectMapper = objectMapper;
    }

    public FinanceRecordExtractionResult process(FinanceRecordExtractionRequest request) {
        validateIdentity(request);
        FinanceRecordDecoder.DecodedBatch decoded = decoder.decode(request.stdout());

        if (!"SUCCEEDED".equals(request.terminalStatus()) || request.exitCode() != 0) {
            observability.processingFailure("terminal_rejected");
            return new FinanceRecordExtractionResult(
                    null, List.of(), decoded.ordinaryStdout(), List.of(), false);
        }
        if (!request.limits().enabled()) {
            List<FinanceRecordExtractionResult.ModelNotice> notices = decoded.records().isEmpty()
                    ? List.of() : List.of(rejectedNotice());
            return new FinanceRecordExtractionResult(
                    null, List.of(), decoded.ordinaryStdout(), notices, false);
        }

        List<String> batchErrors = validateTransport(request, decoded);
        if (!batchErrors.isEmpty()) {
            FinanceRecordBatch batch = buildBatch(request, decoded, false, false, batchErrors, List.of());
            persist(batch, List.of(), "transport_rejected");
            List<FinanceRecordExtractionResult.ModelNotice> notices = shouldWarn(request, decoded)
                    ? List.of(rejectedNotice()) : List.of();
            return new FinanceRecordExtractionResult(
                    batch, List.of(), decoded.ordinaryStdout(), notices, true);
        }

        List<RecordDraft> drafts = new ArrayList<>();
        boolean schemaValid = true;
        for (FinanceRecordDecoder.DecodedRecord record : decoded.records()) {
            FinanceRecordSchemaValidator.ValidationResult validation = schemaValidator.validate(record);
            if (!validation.valid()) {
                schemaValid = false;
            }
            drafts.add(new RecordDraft(record, validation));
        }

        List<FinanceMetricRecord> records = new ArrayList<>();
        List<String> allRecordErrors = new ArrayList<>();
        for (RecordDraft draft : drafts) {
            List<String> errors = new ArrayList<>(draft.validation.errors());
            FinanceMetricRecord record = buildRecord(request, draft.record, schemaValid, errors);
            records.add(record);
            allRecordErrors.addAll(errors);
        }

        List<FinanceRecordExtractionResult.ModelNotice> notices = schemaValid
                ? List.of() : List.of(rejectedNotice());
        FinanceRecordBatch batch = buildBatch(
                request, decoded, schemaValid, schemaValid && !records.isEmpty(),
                allRecordErrors, records);
        persist(batch, records, schemaValid ? "accepted" : "schema_rejected");
        return new FinanceRecordExtractionResult(
                batch, records, decoded.ordinaryStdout(), notices, true);
    }

    private FinanceMetricRecord buildRecord(
            FinanceRecordExtractionRequest request,
            FinanceRecordDecoder.DecodedRecord decoded,
            boolean batchSchemaValid,
            List<String> errors) {
        JsonNode node = decoded.json();
        String sourceResolverToolCallId = text(node, "sourceResolverToolCallId");
        String methodId = text(node, "methodId");
        String methodVersion = text(node, "methodVersion");
        String specDigest = text(node, "specDigest");

        FinanceMethodResolution resolution = null;
        boolean anyMethodIdentity = !methodId.isBlank() || !methodVersion.isBlank() || !specDigest.isBlank();
        boolean completeMethodIdentity = !methodId.isBlank() && !methodVersion.isBlank() && !specDigest.isBlank();
        if (!sourceResolverToolCallId.isBlank() && anyMethodIdentity) {
            if (!completeMethodIdentity) {
                errors.add("SOURCE_METHOD_IDENTITY_INCOMPLETE");
            } else {
                resolution = resolutionQuery.findExact(
                        request.runId(), sourceResolverToolCallId,
                        methodId, methodVersion, specDigest);
                if (resolution == null) {
                    errors.add("SOURCE_RESOLUTION_NOT_FOUND");
                }
            }
        }

        FinanceEnvironmentFact resolverTarget = resolution == null
                ? null : targetEnvironment(resolution, request.targetEnvironment());
        FinanceEnvironmentVerifier.Verification environment = environmentVerifier.verify(
                node, request.executionEnvironment(), resolverTarget);
        errors.addAll(environment.reasons());
        if ((resolution == null && !sourceResolverToolCallId.isBlank() && anyMethodIdentity)
                || errors.contains("SOURCE_METHOD_IDENTITY_INCOMPLETE")) {
            environment = new FinanceEnvironmentVerifier.Verification(
                    environment.declaredEvidence(), FinanceEvidenceLevel.CUSTOM_UNVERIFIED,
                    environment.crossEnvironment(), environment.packageCompatible(), environment.reasons());
        }
        if (environment.crossEnvironment()) {
            observability.crossEnvironment();
        }

        boolean valid = batchSchemaValid && decoded.decodeError() == null;
        String actualEnvironmentId = request.executionEnvironment() == null
                ? "" : request.executionEnvironment().environmentId();
        return FinanceMetricRecord.builder()
                .recordId(recordId(
                        request.runId(), request.todoId(), request.executePythonToolCallId(),
                        decoded.recordIndex(), decoded.rawDigest()))
                .runId(request.runId())
                .todoId(request.todoId())
                .executePythonToolCallId(request.executePythonToolCallId())
                .recordIndex(decoded.recordIndex())
                .rawDigest(decoded.rawDigest())
                .rawPayload(decoded.rawPayload())
                .sourceResolverToolCallId(emptyToNull(sourceResolverToolCallId))
                .methodId(emptyToNull(methodId))
                .methodVersion(emptyToNull(methodVersion))
                .specDigest(emptyToNull(specDigest))
                .valueJson(json(node, "value"))
                .unit(emptyToNull(text(node, "unit")))
                .parametersJson(jsonOr(node, "parameters", "{}"))
                .inputRefsJson(jsonOr(node, "inputRefs", "[]"))
                .checksJson(jsonOr(node, "checks", "{}"))
                .formulaDescription(emptyToNull(text(node, "formulaDescription")))
                .declaredEvidence(environment.declaredEvidence().name())
                .effectiveInternalEvidence(environment.effectiveEvidence().name())
                .actualEnvironmentId(emptyToNull(actualEnvironmentId))
                .renderable(valid)
                .validationErrorJson(toJson(errors))
                .build();
    }

    private List<String> validateTransport(
            FinanceRecordExtractionRequest request,
            FinanceRecordDecoder.DecodedBatch decoded) {
        List<String> errors = new ArrayList<>();
        FinanceRecordChannelMetadata metadata = request.channelMetadata();
        if (metadata == null) {
            errors.add("FINANCE_RECORD_METADATA_MISSING");
            return errors;
        }
        if (!metadata.recordSetComplete()) {
            errors.add("FINANCE_RECORD_SET_INCOMPLETE");
        }
        if (metadata.emittedRecordCount() != decoded.records().size()) {
            errors.add("FINANCE_RECORD_COUNT_MISMATCH");
        }
        if (metadata.emittedRecordBytes() != decoded.emittedRecordBytes()) {
            errors.add("FINANCE_RECORD_BYTES_MISMATCH");
        }
        if (!metadata.recordDigest().equalsIgnoreCase(decoded.recordDigest())) {
            errors.add("FINANCE_RECORD_DIGEST_MISMATCH");
        }
        FinanceRecordChannelLimits limits = request.limits();
        if (decoded.records().size() > limits.recordCountMax()) {
            errors.add("FINANCE_RECORD_COUNT_LIMIT_EXCEEDED");
        }
        if (decoded.emittedRecordBytes() > limits.recordChannelMaxBytes()) {
            errors.add("FINANCE_RECORD_CHANNEL_LIMIT_EXCEEDED");
        }
        if (decoded.records().stream().anyMatch(record -> record.rawBytes() > limits.recordMaxBytes())) {
            errors.add("FINANCE_RECORD_SIZE_LIMIT_EXCEEDED");
        }
        if (decoded.ordinaryStdout().getBytes(StandardCharsets.UTF_8).length > limits.stdoutMaxBytes()) {
            errors.add("FINANCE_STDOUT_LIMIT_EXCEEDED");
        }
        if (request.stderr().getBytes(StandardCharsets.UTF_8).length > limits.stderrMaxBytes()) {
            errors.add("FINANCE_STDERR_LIMIT_EXCEEDED");
        }
        return errors;
    }

    private FinanceRecordBatch buildBatch(
            FinanceRecordExtractionRequest request,
            FinanceRecordDecoder.DecodedBatch decoded,
            boolean schemaValid,
            boolean renderable,
            List<String> errors,
            List<FinanceMetricRecord> records) {
        FinanceRecordChannelMetadata metadata = request.channelMetadata();
        FinanceRecordBatch batch = FinanceRecordBatch.builder()
                .runId(request.runId())
                .todoId(request.todoId())
                .executePythonToolCallId(request.executePythonToolCallId())
                .entryPoint(request.entryPoint())
                .terminalStatus(request.terminalStatus())
                .exitCode(request.exitCode())
                .recordCount(metadata == null ? decoded.records().size() : metadata.emittedRecordCount())
                .recordBytes(metadata == null ? decoded.emittedRecordBytes() : metadata.emittedRecordBytes())
                .recordDigest(metadata == null ? decoded.recordDigest() : metadata.recordDigest())
                .recordSetComplete(metadata != null && metadata.recordSetComplete())
                .dropReason(dropReason(metadata, errors))
                .schemaValid(schemaValid)
                .renderable(renderable)
                .actualEnvironmentJson(toJson(request.executionEnvironment()))
                .validationErrorJson(toJson(errors))
                .build();
        batch.setBatchContentDigest(batchContentDigest(batch, records));
        return batch;
    }

    private void persist(
            FinanceRecordBatch batch,
            List<FinanceMetricRecord> records,
            String outcome) {
        try {
            persister.persist(batch, records);
            observability.persisted(outcome, records.size());
        } catch (FinanceRecordProcessingException exception) {
            observability.processingFailure(exception.getCode());
            throw exception;
        } catch (RuntimeException exception) {
            observability.processingFailure("persistence_unavailable");
            throw new FinanceRecordProcessingException(
                    "FINANCE_RECORD_PERSISTENCE_UNAVAILABLE",
                    "Finance record persistence is temporarily unavailable", exception);
        }
    }

    private FinanceEnvironmentFact targetEnvironment(
            FinanceMethodResolution resolution,
            FinanceEnvironmentFact configuredTarget) {
        String targetId = trim(resolution.getTargetEnvironmentId());
        List<FinanceEnvironmentFact.PackageApi> packages = parsePackageApis(
                resolution.getTargetPackageApiJson());
        if (configuredTarget != null && targetId.equals(configuredTarget.environmentId())) {
            if (packages.isEmpty()) {
                packages = configuredTarget.packageApis();
            }
            return new FinanceEnvironmentFact(
                    targetId, configuredTarget.imageDigest(), configuredTarget.librarySetDigest(),
                    packages, configuredTarget.inventoryComplete());
        }
        return targetId.isEmpty() && packages.isEmpty() ? null
                : new FinanceEnvironmentFact(targetId, "", "", packages, !packages.isEmpty());
    }

    private List<FinanceEnvironmentFact.PackageApi> parsePackageApis(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode array = root.isArray() ? root : root.path("packageApis");
            if (!array.isArray()) {
                return List.of();
            }
            List<FinanceEnvironmentFact.PackageApi> result = new ArrayList<>();
            for (JsonNode item : array) {
                result.add(new FinanceEnvironmentFact.PackageApi(
                        text(item, "name"), text(item, "version"), text(item, "apiVersion")));
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String batchContentDigest(
            FinanceRecordBatch batch,
            List<FinanceMetricRecord> records) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("runId", batch.getRunId());
        content.put("todoId", batch.getTodoId());
        content.put("executePythonToolCallId", batch.getExecutePythonToolCallId());
        // entryPoint records which terminal path won the first durable write, but it is not
        // business content. A sync write followed by async ENVELOPE recovery must replay as the
        // same executePython batch instead of becoming an identity conflict solely because the
        // processing path changed.
        content.put("terminalStatus", batch.getTerminalStatus());
        content.put("exitCode", batch.getExitCode());
        content.put("recordCount", batch.getRecordCount());
        content.put("recordBytes", batch.getRecordBytes());
        content.put("recordDigest", batch.getRecordDigest());
        content.put("recordSetComplete", batch.getRecordSetComplete());
        content.put("dropReason", batch.getDropReason());
        content.put("schemaValid", batch.getSchemaValid());
        content.put("renderable", batch.getRenderable());
        content.put("actualEnvironment", parseJsonOrText(batch.getActualEnvironmentJson()));
        content.put("validationErrors", parseJsonOrText(batch.getValidationErrorJson()));
        content.put("records", records.stream().map(record -> Map.of(
                "recordIndex", record.getRecordIndex(),
                "recordId", record.getRecordId(),
                "rawDigest", record.getRawDigest(),
                "effectiveEvidence", record.getEffectiveInternalEvidence(),
                "renderable", record.getRenderable())).toList());
        try {
            return FinanceRecordDecoder.sha256Hex(
                    objectMapper.writeValueAsBytes(content));
        } catch (Exception exception) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RECORD_BATCH_DIGEST_FAILED",
                    "Unable to compute finance record batch content digest", exception);
        }
    }

    private Object parseJsonOrText(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return json;
        }
    }

    /** Each tuple component is UTF-8 encoded as uint32be(length) || bytes, including decimal recordIndex. */
    public static String recordId(
            String runId,
            String todoId,
            String executePythonToolCallId,
            int recordIndex,
            String rawDigest) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        appendLengthPrefixed(output, trim(runId));
        appendLengthPrefixed(output, trim(todoId));
        appendLengthPrefixed(output, trim(executePythonToolCallId));
        appendLengthPrefixed(output, Integer.toString(recordIndex));
        appendLengthPrefixed(output, trim(rawDigest));
        return FinanceRecordDecoder.sha256Hex(output.toByteArray());
    }

    private static void appendLengthPrefixed(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        output.writeBytes(bytes);
    }

    private void validateIdentity(FinanceRecordExtractionRequest request) {
        if (request == null) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RECORD_REQUEST_MISSING", "Finance extraction request is required");
        }
        if (request.runId().isBlank() || request.todoId().isBlank()
                || request.executePythonToolCallId().isBlank() || request.taskId().isBlank()) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RECORD_IDENTITY_MISSING",
                    "runId, todoId, executePythonToolCallId, and taskId are required");
        }
        if (request.limits() == null) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RECORD_LIMITS_MISSING", "Frozen finance record limits are required");
        }
    }

    private static boolean shouldWarn(
            FinanceRecordExtractionRequest request,
            FinanceRecordDecoder.DecodedBatch decoded) {
        return !decoded.records().isEmpty()
                || (request.channelMetadata() != null
                && (request.channelMetadata().emittedRecordCount() > 0
                || !request.channelMetadata().recordSetComplete()));
    }

    private static FinanceRecordExtractionResult.ModelNotice rejectedNotice() {
        return new FinanceRecordExtractionResult.ModelNotice(
                "FINANCE_RESULT_REJECTED",
                "本次结构化金融结果没有被接收",
                "检查 report_custom() 必填字段或减少单批记录数量后重试");
    }

    private static String dropReason(
            FinanceRecordChannelMetadata metadata,
            List<String> errors) {
        if (metadata != null && !metadata.dropReason().isBlank()) {
            return metadata.dropReason();
        }
        return errors.isEmpty() ? "" : errors.get(0);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RECORD_JSON_ENCODING_FAILED",
                    "Unable to encode finance audit JSON", exception);
        }
    }

    private static String json(JsonNode node, String field) {
        return node == null || !node.has(field) ? null : node.get(field).toString();
    }

    private static String jsonOr(JsonNode node, String field, String fallback) {
        String value = json(node, field);
        return value == null ? fallback : value;
    }

    private static String text(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) || !node.get(field).isTextual()
                ? "" : node.get(field).asText().trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record RecordDraft(
            FinanceRecordDecoder.DecodedRecord record,
            FinanceRecordSchemaValidator.ValidationResult validation) {
    }
}
