package world.willfrog.agent.platform.finance;

/** Immutable Java-side limits frozen into a run and tool-job anchor. */
public record FinanceRecordChannelLimits(
        boolean enabled,
        int recordCountMax,
        int recordMaxBytes,
        int recordChannelMaxBytes,
        int stdoutMaxBytes,
        int stderrMaxBytes,
        String targetEnvironmentId) {

    public FinanceRecordChannelLimits {
        requirePositive("recordCountMax", recordCountMax);
        requirePositive("recordMaxBytes", recordMaxBytes);
        requirePositive("recordChannelMaxBytes", recordChannelMaxBytes);
        requirePositive("stdoutMaxBytes", stdoutMaxBytes);
        requirePositive("stderrMaxBytes", stderrMaxBytes);
        targetEnvironmentId = targetEnvironmentId == null ? "" : targetEnvironmentId.trim();
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
