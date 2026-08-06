package world.willfrog.agentlangchain.config;

/**
 * 一次解析后的 Run 调度上限快照。
 *
 * <p>core 是稳定并发，max 是队列打满后可临时扩到的上限，queueCapacity 是业务调度器
 * 自己维护的 FIFO 容量。构造器在边界处统一归一化，确保 core≤max、max≥1、queue≥0，
 * 后续调度热路径不再重复处理非法配置。</p>
 */
public class LangchainRunExecutorLimits {

    private final int corePoolSize;
    private final int maxPoolSize;
    private final int queueCapacity;
    private final String threadNamePrefix;

    public LangchainRunExecutorLimits(int corePoolSize,
                                      int maxPoolSize,
                                      int queueCapacity,
                                      String threadNamePrefix) {
        // 先确定 max，再把 core 截断到 [1,max]，避免线程池配置互相矛盾。
        int normalizedMax = Math.max(1, maxPoolSize);
        int normalizedCore = Math.max(1, Math.min(corePoolSize, normalizedMax));
        this.corePoolSize = normalizedCore;
        this.maxPoolSize = normalizedMax;
        this.queueCapacity = Math.max(0, queueCapacity);
        // 线程名前缀只影响诊断，不允许空值导致难以识别 worker。
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
