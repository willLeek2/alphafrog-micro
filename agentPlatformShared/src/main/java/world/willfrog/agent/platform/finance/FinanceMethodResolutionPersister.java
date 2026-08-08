package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.mapper.FinanceMethodResolutionMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Atomically persists one resolver invocation's validated suggestion set. */
@Component
public class FinanceMethodResolutionPersister {
    private final FinanceMethodResolutionMapper mapper;
    private final ObjectMapper objectMapper;

    public FinanceMethodResolutionPersister(
            FinanceMethodResolutionMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional
    public void persistBatch(List<FinanceMethodResolution> resolutions) {
        if (resolutions == null || resolutions.isEmpty()) {
            return;
        }
        String runId = required(resolutions.get(0).getRunId(), "runId");
        String toolCallId = required(resolutions.get(0).getResolverToolCallId(), "resolverToolCallId");
        for (FinanceMethodResolution resolution : resolutions) {
            if (!runId.equals(required(resolution.getRunId(), "runId"))
                    || !toolCallId.equals(required(resolution.getResolverToolCallId(), "resolverToolCallId"))) {
                throw new FinanceRecordProcessingException(
                        "FINANCE_RESOLUTION_BATCH_IDENTITY_INVALID",
                        "All resolver suggestions in one transaction must share runId and resolverToolCallId");
            }
            normalizeJson(resolution);
            // The digest is a trusted persistence fact, never caller-supplied input.
            resolution.setResolutionContentDigest(contentDigest(resolution));
        }
        for (FinanceMethodResolution resolution : resolutions) {
            int inserted = mapper.insertIgnore(resolution);
            if (inserted == 1) {
                continue;
            }
            FinanceMethodResolution existing = mapper.findExact(
                    resolution.getRunId(), resolution.getResolverToolCallId(),
                    resolution.getMethodId(), resolution.getMethodVersion(), resolution.getSpecDigest());
            if (existing == null || !Objects.equals(
                    existing.getResolutionContentDigest(), resolution.getResolutionContentDigest())) {
                throw new FinanceRecordProcessingException(
                        "FINANCE_RESOLUTION_IDENTITY_CONFLICT",
                        "Resolver suggestion identity already exists with different content");
            }
        }
    }

    private String contentDigest(FinanceMethodResolution row) {
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("runId", row.getRunId());
            content.put("resolverToolCallId", row.getResolverToolCallId());
            content.put("todoId", row.getTodoId());
            content.put("methodId", row.getMethodId());
            content.put("methodVersion", row.getMethodVersion());
            content.put("specDigest", row.getSpecDigest());
            content.put("catalogDigest", row.getCatalogDigest());
            content.put("resolverSchemaVersion", row.getResolverSchemaVersion());
            content.put("resolverPromptVersion", row.getResolverPromptVersion());
            content.put("modelRouteJson", parseJson(row.getModelRouteJson()));
            content.put("matchReason", row.getMatchReason());
            content.put("clarificationJson", parseJson(row.getClarificationJson()));
            content.put("targetEnvironmentId", row.getTargetEnvironmentId());
            content.put("targetPackageApiJson", parseJson(row.getTargetPackageApiJson()));
            content.put("resolutionPayloadJson", parseJson(row.getResolutionPayloadJson()));
            return FinanceRecordDecoder.sha256Hex(
                    objectMapper.writeValueAsString(content).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RESOLUTION_DIGEST_FAILED",
                    "Unable to compute resolver snapshot content digest", exception);
        }
    }

    private Object parseJson(String json) throws Exception {
        return objectMapper.readValue(json, Object.class);
    }

    private static void normalizeJson(FinanceMethodResolution row) {
        row.setRunId(required(row.getRunId(), "runId"));
        row.setResolverToolCallId(required(row.getResolverToolCallId(), "resolverToolCallId"));
        row.setTodoId(required(row.getTodoId(), "todoId"));
        row.setMethodId(required(row.getMethodId(), "methodId"));
        row.setMethodVersion(required(row.getMethodVersion(), "methodVersion"));
        row.setSpecDigest(required(row.getSpecDigest(), "specDigest"));
        row.setCatalogDigest(required(row.getCatalogDigest(), "catalogDigest"));
        row.setResolverSchemaVersion(required(
                row.getResolverSchemaVersion(), "resolverSchemaVersion"));
        row.setResolverPromptVersion(required(
                row.getResolverPromptVersion(), "resolverPromptVersion"));
        row.setMatchReason(required(row.getMatchReason(), "matchReason"));
        row.setTargetEnvironmentId(trimToNull(row.getTargetEnvironmentId()));
        row.setModelRouteJson(jsonObject(row.getModelRouteJson()));
        row.setClarificationJson(jsonArray(row.getClarificationJson()));
        row.setTargetPackageApiJson(jsonArray(row.getTargetPackageApiJson()));
        row.setResolutionPayloadJson(jsonObject(row.getResolutionPayloadJson()));
    }

    private static String jsonObject(String value) { return blank(value) ? "{}" : value; }
    private static String jsonArray(String value) { return blank(value) ? "[]" : value; }

    private static String required(String value, String field) {
        if (blank(value)) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RESOLUTION_IDENTITY_INVALID", field + " is required");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
