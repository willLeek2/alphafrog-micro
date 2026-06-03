package world.willfrog.agentlangchain.config;

public class LangchainRunExecutorLimits {

    private final int corePoolSize;
    private final int maxPoolSize;
    private final int queueCapacity;
    private final String threadNamePrefix;

    public LangchainRunExecutorLimits(int corePoolSize,
                                      int maxPoolSize,
                                      int queueCapacity,
                                      String threadNamePrefix) {
        int normalizedMax = Math.max(1, maxPoolSize);
        int normalizedCore = Math.max(1, Math.min(corePoolSize, normalizedMax));
        this.corePoolSize = normalizedCore;
        this.maxPoolSize = normalizedMax;
        this.queueCapacity = Math.max(0, queueCapacity);
        this.threadNamePrefix = threadNamePrefix == null || threadNamePrefix.isBlank()
                ? "agent-langchain-run-"
                : threadNamePrefix;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public String summary() {
        return "core=" + corePoolSize
                + ", max=" + maxPoolSize
                + ", queue=" + queueCapacity;
    }
}
