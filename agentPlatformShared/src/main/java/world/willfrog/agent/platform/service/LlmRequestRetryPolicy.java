package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.config.AgentLlmProperties;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;

public class LlmRequestRetryPolicy {

    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final long DEFAULT_BASE_DELAY_MS = 1000L;
    public static final long DEFAULT_MAX_DELAY_MS = 4000L;
    public static final long DEFAULT_JITTER_MS = 100L;
    public static final String BACKOFF_FIXED = "fixed";
    public static final String BACKOFF_EXPONENTIAL = "exponential";

    private final int maxRetries;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final long jitterMs;
    private final String backoffType;
    private final LongConsumer sleeper;

    public LlmRequestRetryPolicy(int maxRetries, long retryDelayMs, LongConsumer sleeper) {
        this(maxRetries, retryDelayMs, retryDelayMs, 0L, BACKOFF_FIXED, sleeper);
    }

    public LlmRequestRetryPolicy(int maxRetries,
                                 long baseDelayMs,
                                 long maxDelayMs,
                                 long jitterMs,
                                 String backoffType,
                                 LongConsumer sleeper) {
        this.maxRetries = Math.max(0, maxRetries);
        this.baseDelayMs = Math.max(0L, baseDelayMs);
        this.maxDelayMs = Math.max(this.baseDelayMs, maxDelayMs);
        this.jitterMs = Math.max(0L, jitterMs);
        this.backoffType = normalizeBackoff(backoffType);
        this.sleeper = sleeper == null ? LlmRequestRetryPolicy::sleep : sleeper;
    }

    public static LlmRequestRetryPolicy withMaxRetries(Integer maxRetries) {
        return withConfig(maxRetries, null);
    }

    public static LlmRequestRetryPolicy withConfig(Integer maxRetries, AgentLlmProperties.Retry retry) {
        int retries = maxRetries == null ? DEFAULT_MAX_RETRIES : Math.max(0, maxRetries);
        long baseDelay = retry == null || retry.getBaseDelayMs() == null
                ? DEFAULT_BASE_DELAY_MS : retry.getBaseDelayMs();
        long maxDelay = retry == null || retry.getMaxDelayMs() == null
                ? DEFAULT_MAX_DELAY_MS : retry.getMaxDelayMs();
        long jitter = retry == null || retry.getJitterMs() == null
                ? DEFAULT_JITTER_MS : retry.getJitterMs();
        String backoff = retry == null ? BACKOFF_EXPONENTIAL : retry.getBackoffType();
        return new LlmRequestRetryPolicy(retries, baseDelay, maxDelay, jitter, backoff, LlmRequestRetryPolicy::sleep);
    }

    public int maxRetries() {
        return maxRetries;
    }

    public int maxAttempts() {
        return maxRetries + 1;
    }

    public void checkAttemptBudget(int attempt) {
        if (attempt < 1 || attempt > maxAttempts()) {
            throw new IllegalStateException("LLM request retry attempts exhausted: attempt=" + attempt
                    + ", maxAttempts=" + maxAttempts());
        }
    }

    public boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    public boolean shouldRetryStatus(int status, int attempt) {
        return isRetryableStatus(status) && attempt < maxAttempts();
    }

    public boolean isRetryableException(Exception e) {
        return e instanceof IOException;
    }

    public boolean shouldRetryException(Exception e, int attempt) {
        return isRetryableException(e) && attempt < maxAttempts();
    }

    public void sleepBeforeRetry(int completedAttempt) throws InterruptedException {
        long delay = delayMs(completedAttempt);
        if (delay <= 0) {
            return;
        }
        try {
            sleeper.accept(delay);
        } catch (RetryInterruptedException e) {
            throw e.interruptedException;
        }
    }

    long delayMs(int completedAttempt) {
        long base = baseDelayMs;
        if (BACKOFF_EXPONENTIAL.equals(backoffType)) {
            int exponent = Math.max(0, completedAttempt - 1);
            long multiplier = exponent >= 30 ? Long.MAX_VALUE : (1L << exponent);
            base = saturatingMultiply(baseDelayMs, multiplier);
        }
        long bounded = Math.min(base, maxDelayMs);
        if (jitterMs <= 0) {
            return bounded;
        }
        long jitter = ThreadLocalRandom.current().nextLong(jitterMs + 1);
        return Math.min(maxDelayMs, bounded + jitter);
    }

    private static long saturatingMultiply(long left, long right) {
        if (left == 0 || right == 0) {
            return 0;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static String normalizeBackoff(String backoffType) {
        String normalized = backoffType == null ? "" : backoffType.trim().toLowerCase(Locale.ROOT);
        return BACKOFF_FIXED.equals(normalized) ? BACKOFF_FIXED : BACKOFF_EXPONENTIAL;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryInterruptedException(e);
        }
    }

    private static class RetryInterruptedException extends RuntimeException {
        private final InterruptedException interruptedException;

        private RetryInterruptedException(InterruptedException interruptedException) {
            super(interruptedException);
            this.interruptedException = interruptedException;
        }
    }
}
