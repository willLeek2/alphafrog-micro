package world.willfrog.agent.platform.model;

/**
 * Agent run event broadcast envelope sent over Redis pub/sub.
 *
 * <p>The durable source of truth is still {@code alphafrog_agent_run_event}.
 * This envelope is a lightweight live-notification copy used by the frontend
 * SSE bridge. Consumers should de-duplicate by {@code runId + seq} and use the
 * REST events endpoint to repair any missed history. {@code createdAt} is only
 * a display timestamp; ordering must use {@code seq} because DB {@code created_at}
 * is generated separately from the live envelope timestamp.</p>
 */
public record AgentRunEventEnvelope(
        String runId,
        int seq,
        String eventType,
        String payloadJson,
        String createdAt
) {
}
