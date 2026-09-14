package world.willfrog.alphafrogmicro.frontend.service.debug;

import lombok.Data;

import java.util.concurrent.atomic.AtomicLong;

@Data
public class AuthObservabilitySession {

    private final String debugSessionId;
    private final long createdAt;
    private final long ttlDeadline;
    private final String outputDir;
    private final String operator;
    private final String reason;
    private final AuthObservabilityScope scope;

    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong linesWritten = new AtomicLong();
    private final AtomicLong droppedByCapacity = new AtomicLong();
    private final AtomicLong droppedBySensitiveFilter = new AtomicLong();
    private final AtomicLong writerErrorCount = new AtomicLong();

    private volatile boolean enabled = true;
    private volatile String stoppedReason;

    public boolean isExpired(long now) {
        return now > ttlDeadline;
    }

    public long getRemainingTtlSeconds(long now) {
        long remaining = (ttlDeadline - now) / 1000;
        return Math.max(0, remaining);
    }
}
