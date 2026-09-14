package world.willfrog.agent.platform.artifact;

/**
 * 工具输出 rawRef 服务。
 *
 * <p>D22-5.1.3：每个操作提供显式上下文 overload（runId/userId 显式传入），
 * 不依赖 {@link world.willfrog.agent.platform.context.AgentContext} 线程态；
 * 旧入口保留为有界兼容 delegate（从 AgentContext 补齐上下文后转调显式 overload）。</p>
 */
public interface ToolOutputRefService {
    PersistentArtifactRegistration registerRawOutput(String logicalId, String displayName, String content);

    /** D22-5.1.3：显式上下文注册入口。 */
    PersistentArtifactRegistration registerRawOutput(String runId, String userId, String logicalId,
                                                     String displayName, String content);

    PersistentArtifactRegistration rebindFromLocator(String logicalId, String displayName, RawPayloadLocator locator);

    RawPayloadLocator locatorFor(String rawRef);

    /** D22-5.1.3：显式上下文定位入口，归属按 meta runId/userId 校验。 */
    RawPayloadLocator locatorFor(String runId, String userId, String rawRef);

    ToolOutputReadResult read(String rawRef, int offset, int limit, String keyword);

    /** D22-5.1.3：显式上下文读取入口，归属按 meta runId/userId 校验。 */
    ToolOutputReadResult read(String runId, String userId, String rawRef, int offset, int limit, String keyword);
}
