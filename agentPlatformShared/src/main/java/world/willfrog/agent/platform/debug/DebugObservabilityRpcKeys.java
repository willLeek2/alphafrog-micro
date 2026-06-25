package world.willfrog.agent.platform.debug;

/**
 * Dubbo attachment keys for cross-service debug observability propagation.
 */
public final class DebugObservabilityRpcKeys {

    public static final String SESSION_ID = "af.debugObservability.sessionId";
    public static final String RUN_ID = "af.debugObservability.runId";
    public static final String SESSION_DIR = "af.debugObservability.sessionDir";

    private DebugObservabilityRpcKeys() {
    }
}
