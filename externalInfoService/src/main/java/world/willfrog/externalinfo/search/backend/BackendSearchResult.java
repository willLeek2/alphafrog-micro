package world.willfrog.externalinfo.search.backend;

import java.util.List;

/**
 * 各 backend 原始响应的统一结构。
 * 由具体 backend 实现填充，再由 ResultNormalizer 映射为 WebSearchResponse。
 *
 * <p>{@code retryCount} tracks how many HTTP attempts the backend made to
 * produce this result (1 = succeeded/failed on first try). It is informational
 * and used by observability / fallback logic to decide whether a request was
 * degraded. Set to {@code null} when the request never reached the HTTP layer
 * (e.g. config-missing or local pre-flight failures).</p>
 */
public record BackendSearchResult(
        List<BackendHit> hits,
        String answer,
        List<BackendCitation> citations,
        BackendMeta meta,
        boolean ok,
        String errorCode,
        String errorMessage,
        Integer retryCount
) {

    public static BackendSearchResult error(String backendName, String errorCode, String errorMessage) {
        return new BackendSearchResult(
                List.of(), null, List.of(),
                new BackendMeta(backendName, null, null, null),
                false, errorCode, errorMessage, null
        );
    }

    public static BackendSearchResult error(String backendName, String errorCode, String errorMessage,
                                            Integer retryCount) {
        return new BackendSearchResult(
                List.of(), null, List.of(),
                new BackendMeta(backendName, null, null, null),
                false, errorCode, errorMessage, retryCount
        );
    }
}
