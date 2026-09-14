package world.willfrog.agent.platform.finance;

/** Presence-aware projection of proto field 10. A null instance means absent. */
public record FinanceRecordChannelMetadata(
        int emittedRecordCount,
        long emittedRecordBytes,
        boolean recordSetComplete,
        String dropReason,
        String recordDigest,
        boolean stdoutTruncated,
        boolean stderrTruncated) {

    public FinanceRecordChannelMetadata {
        dropReason = normalize(dropReason);
        recordDigest = normalize(recordDigest);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
