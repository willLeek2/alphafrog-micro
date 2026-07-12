package world.willfrog.agent.platform.dataanalysis;

/**
 * Durable handoff used by {@code PythonSandboxTools} without creating an
 * agentToolsShared -> agentLangchainService dependency.
 */
public interface PythonSandboxDispatchStore {

    boolean persistPreparing(String runId, ToolJobAnchor anchor);

    boolean persistAttached(String runId, ToolJobAnchor anchor);

    boolean transferToPending(String runId, ToolJobAnchor anchor);

    boolean clearActive(String runId, String operationId);
}
