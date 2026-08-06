package world.willfrog.agent.platform.artifact;

/**
 * Run-scoped short-ID mapping layer for rawRef.
 *
 * <p>Agent-visible IDs follow the format {@code raw_ref_001}, {@code raw_ref_002}, etc.
 * The mapping from short ID to the internal {@link PersistentArtifactRegistry} artifact ID
 * is stored in Redis with the same TTL as the underlying content artifact.</p>
 */
public interface RunRawRefStore {

    /** Register content and return a run-scoped short ID (e.g. {@code raw_ref_001}). */
    String register(String runId, String userId, String displayName, String content, long ttlSeconds);

    /** Read full content by short ID. */
    String read(String runId, String shortId);

    /** Read a window of content by short ID. */
    ToolOutputReadResult read(String runId, String shortId, int offset, int limit, String keyword);

    /** Check whether the given short ID belongs to the specified run. */
    boolean belongsToRun(String runId, String shortId);
}
