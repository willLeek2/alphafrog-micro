package world.willfrog.agent.platform.artifact;

/**
 * Run-scoped short-ID mapping layer for rawRef.
 *
 * <p>Agent-visible IDs follow the format {@code raw_ref_001}, {@code raw_ref_002}, etc.
 * The mapping from short ID to the internal {@link PersistentArtifactRegistry} artifact ID
 * is stored in Redis with the same TTL as the underlying content artifact.</p>
 *
 * <p>Ownership（归属校验）：映射层本身只按 runId 解析短 ID → artifactId；内容读取一律
 * 经 {@link PersistentArtifactRegistry#readContentStrict} 做四值严格归属校验——调用方
 * 的 runId 与 userId 必须与制品 meta 严格相等，任一空白或不一致 fail-closed 拒绝。
 * 因此即使 runId 正确，userId 不匹配（或空白）的调用方也读不到内容：短格式 raw_ref
 * 不存在只凭 runId 放行的读取路径。</p>
 */
public interface RunRawRefStore {

    /** Register content and return a run-scoped short ID (e.g. {@code raw_ref_001}). */
    String register(String runId, String userId, String displayName, String content, long ttlSeconds);

    /**
     * Read full content by short ID. 严格归属校验：runId 与 userId 均须与制品 meta
     * 严格相等（空白或不一致一律拒绝）。
     */
    String read(String runId, String userId, String shortId);

    /**
     * Read a window of content by short ID. 严格归属校验同 {@link #read(String, String, String)}。
     */
    ToolOutputReadResult read(String runId, String userId, String shortId, int offset, int limit, String keyword);

    /** Check whether the given short ID belongs to the specified run. */
    boolean belongsToRun(String runId, String shortId);
}
