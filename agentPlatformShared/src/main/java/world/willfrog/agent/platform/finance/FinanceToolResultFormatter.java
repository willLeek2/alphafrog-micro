package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes the byte-identical model-facing {@code executePython} result used by sync and async
 * terminal paths.
 *
 * <p>The formatter only accepts model-safe projections. Backend identities, digests, environment
 * facts, raw references, and finance marker payloads have no place in this API. Every returned
 * value is complete JSON within the durable terminal preview limit; JSON is never byte-truncated.
 * Structured finance results and actionable notices take priority over ordinary stdout.</p>
 */
@Component
public class FinanceToolResultFormatter {

    private static final String TOOL = "executePython";
    private static final String TRUNCATION_SUFFIX = "…[truncated]";
    private static final FinanceRecordExtractionResult.ModelNotice SUMMARY_TRUNCATED_NOTICE =
            new FinanceRecordExtractionResult.ModelNotice(
                    "FINANCE_RESULT_SUMMARY_TRUNCATED",
                    "结构化计算结果过多，当前工具结果只提供部分条目",
                    "缩小计算范围或分批执行后重试");

    private final ObjectMapper objectMapper;

    public FinanceToolResultFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Formats a successful terminal result. Empty optional fields are omitted from {@code data}.
     */
    public String formatSuccess(
            String ordinaryStdout,
            List<FinanceModelResult> results,
            List<FinanceRecordExtractionResult.ModelNotice> notices) {
        rejectMarkerFamily(ordinaryStdout, "ordinary stdout");
        List<FinanceModelResult> includedResults = new ArrayList<>();
        List<FinanceRecordExtractionResult.ModelNotice> includedNotices = new ArrayList<>();
        boolean omittedStructuredContent = false;

        if (notices != null) {
            for (FinanceRecordExtractionResult.ModelNotice notice : notices) {
                if (notice == null) {
                    continue;
                }
                List<FinanceRecordExtractionResult.ModelNotice> candidate =
                        appended(includedNotices, notice);
                if (fits(successPayload(null, includedResults, candidate))) {
                    includedNotices.add(notice);
                } else {
                    omittedStructuredContent = true;
                }
            }
        }

        if (results != null) {
            for (FinanceModelResult result : results) {
                if (result == null) {
                    continue;
                }
                List<FinanceModelResult> candidate = appended(includedResults, result);
                if (fits(successPayload(null, candidate, includedNotices))) {
                    includedResults.add(result);
                } else {
                    omittedStructuredContent = true;
                }
            }
        }

        if (omittedStructuredContent) {
            makeRoomForTruncationNotice(includedResults, includedNotices);
            includedNotices.add(SUMMARY_TRUNCATED_NOTICE);
        }

        String fittedStdout = fitText(
                ordinaryStdout,
                value -> successPayload(value, includedResults, includedNotices));
        return writeBounded(successPayload(fittedStdout, includedResults, includedNotices));
    }

    /**
     * Formats a failed, canceled, or result-lost terminal result. The caller supplies the public
     * error classification; this formatter guarantees the allowlist and byte bound.
     */
    public String formatFailure(
            String ordinaryStdout,
            String stderr,
            FailureDetail failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure detail is required");
        }
        rejectMarkerFamily(ordinaryStdout, "ordinary stdout");
        rejectMarkerFamily(stderr, "stderr");

        String fittedStderr = fitText(
                stderr,
                value -> failurePayload(null, value, failure));
        String fittedStdout = fitText(
                ordinaryStdout,
                value -> failurePayload(value, fittedStderr, failure));
        return writeBounded(failurePayload(fittedStdout, fittedStderr, failure));
    }

    private void makeRoomForTruncationNotice(
            List<FinanceModelResult> results,
            List<FinanceRecordExtractionResult.ModelNotice> notices) {
        List<FinanceRecordExtractionResult.ModelNotice> withTruncation =
                appended(notices, SUMMARY_TRUNCATED_NOTICE);
        while (!fits(successPayload(null, results, withTruncation))) {
            if (!results.isEmpty()) {
                results.remove(results.size() - 1);
                continue;
            }
            if (!notices.isEmpty()) {
                notices.remove(notices.size() - 1);
                withTruncation = appended(notices, SUMMARY_TRUNCATED_NOTICE);
                continue;
            }
            throw new IllegalStateException("minimal finance truncation notice exceeds preview limit");
        }
    }

    private Map<String, Object> successPayload(
            String stdout,
            List<FinanceModelResult> results,
            List<FinanceRecordExtractionResult.ModelNotice> notices) {
        Map<String, Object> data = new LinkedHashMap<>();
        putText(data, "stdout", stdout);
        if (results != null && !results.isEmpty()) {
            data.put("results", results.stream().map(this::resultPayload).toList());
        }
        if (notices != null && !notices.isEmpty()) {
            data.put("warnings", notices.stream().map(this::noticePayload).toList());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", TOOL);
        payload.put("data", data);
        payload.put("error", null);
        return payload;
    }

    private Map<String, Object> failurePayload(
            String stdout,
            String stderr,
            FailureDetail failure) {
        Map<String, Object> data = new LinkedHashMap<>();
        putText(data, "stdout", stdout);
        putText(data, "stderr", stderr);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", requiredText(failure.code(), "failure code"));
        error.put("message", requiredText(failure.message(), "failure message"));
        error.put("retryable", failure.retryable());
        error.put("action", requiredText(failure.action(), "failure action"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", TOOL);
        payload.put("data", data);
        payload.put("error", error);
        return payload;
    }

    private Map<String, Object> resultPayload(FinanceModelResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", requiredText(result.method(), "result method"));
        if (result.value() == null) {
            throw new IllegalArgumentException("result value is required");
        }
        payload.put("value", result.value());
        payload.put("unit", requiredText(result.unit(), "result unit"));
        payload.put("howCalculated", requiredText(result.howCalculated(), "result howCalculated"));
        return payload;
    }

    private Map<String, Object> noticePayload(FinanceRecordExtractionResult.ModelNotice notice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", requiredText(notice.code(), "notice code"));
        payload.put("message", requiredText(notice.message(), "notice message"));
        payload.put("action", requiredText(notice.action(), "notice action"));
        return payload;
    }

    private String fitText(String text, PayloadFactory payloadFactory) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (fits(payloadFactory.create(text))) {
            return text;
        }

        int[] codePoints = text.codePoints().toArray();
        int low = 0;
        int high = codePoints.length;
        String best = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            String candidate = new String(codePoints, 0, middle) + TRUNCATION_SUFFIX;
            if (fits(payloadFactory.create(candidate))) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private boolean fits(Map<String, Object> payload) {
        return write(payload).getBytes(StandardCharsets.UTF_8).length
                <= DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES;
    }

    private String writeBounded(Map<String, Object> payload) {
        String json = write(payload);
        if (json.getBytes(StandardCharsets.UTF_8).length
                > DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES) {
            throw new IllegalStateException("finance model result exceeds durable preview limit");
        }
        return json;
    }

    private String write(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("unable to serialize finance model result", exception);
        }
    }

    private static void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        rejectMarkerFamily(value, label);
        return value;
    }

    private static void rejectMarkerFamily(String value, String label) {
        if (value != null && value.contains(FinanceRecordDecoder.MARKER_FAMILY)) {
            throw new IllegalArgumentException(label + " still contains a finance marker");
        }
    }

    private static <T> List<T> appended(List<T> source, T value) {
        List<T> result = new ArrayList<>(source);
        result.add(value);
        return result;
    }

    @FunctionalInterface
    private interface PayloadFactory {
        Map<String, Object> create(String value);
    }

    /** Model-safe projection produced by the canonical MethodSpec projector. */
    public record FinanceModelResult(
            String method,
            Number value,
            String unit,
            String howCalculated) {
    }

    /** Public, actionable failure details; no backend metadata is accepted. */
    public record FailureDetail(
            String code,
            String message,
            boolean retryable,
            String action) {
    }
}
