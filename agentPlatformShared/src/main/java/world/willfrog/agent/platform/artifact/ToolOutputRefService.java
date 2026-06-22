package world.willfrog.agent.platform.artifact;

public interface ToolOutputRefService {
    PersistentArtifactRegistration registerRawOutput(String logicalId, String displayName, String content);

    PersistentArtifactRegistration rebindFromLocator(String logicalId, String displayName, RawPayloadLocator locator);

    RawPayloadLocator locatorFor(String rawRef);

    ToolOutputReadResult read(String rawRef, int offset, int limit, String keyword);
}
