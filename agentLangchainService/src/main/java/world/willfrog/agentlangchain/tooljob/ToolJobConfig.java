package world.willfrog.agentlangchain.tooljob;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.tool-job")
public class ToolJobConfig {

    /**
     * 工具同步快路径的最长等待时间（毫秒）。
     * durable adapter 在该窗口内完成就直接返回；超过窗口后必须先写 ToolJob anchor 和
     * checkpoint，再让 Agent Run 进入 WAITING_TOOL_JOB 并释放 run worker。
     */
    private long fastPathMs = 1500;

    /** reconciler 扫描到期 ToolJob 的轮询间隔；它负责发现 Sandbox 终态，不是 run worker。 */
    private long reconcilerIntervalMs = 5000;

    /** 单个 ToolJob 尚未终态时的下一次 Sandbox 状态检查间隔。 */
    private long pollIntervalMs = 1000;

    /** 工具没有显式 timeout 时采用的 Sandbox 默认超时秒数。 */
    private int defaultTimeoutSeconds = 300;

    /** 已确认终态后拉取结果的最大次数；超过后进入 RESULT_LOST 收口。 */
    private int resultFetchMaxAttempts = 10;

    /** 终态结果可重试拉取的总保留时间；到期后必须明确写 RESULT_LOST，不能无限占容量。 */
    private long resultRetentionDeadlineSeconds = 600;

    /** Sandbox timeout后的额外终态保留时间，用于兼容清理与迟到结果。 */
    private long terminalRetentionSeconds = 300;

    /** READY→LAUNCHING 租约未被执行线程确认的最长时间；超时后允许回滚并重新抢占。 */
    private long launchingStaleSeconds = 120;

    /** 恢复 launcher 的持久化租约时长；活跃 launcher 必须在过期前续租。 */
    private long resumeLauncherLeaseSeconds = 30;

    /**
     * 跨进程耐久恢复总开关。false（默认）时不创建 ToolJobReconciler、
     * ToolJobStartupRecovery 和 resume launcher heartbeat，长工具终态由进程内
     * ToolJobContinuationTracker 跟踪；true 时恢复原 Redis/PG 扫描接管链。
     */
    private boolean durableRecoveryEnabled = false;

    /** 进程内 continuation tracker 连续轮询 RPC 失败上限；超过后按 RESULT_LOST 收口。 */
    private int continuationMaxConsecutivePollFailures = 5;

    public long getFastPathMs() { return fastPathMs; }
    public void setFastPathMs(long fastPathMs) { this.fastPathMs = fastPathMs; }

    public long getReconcilerIntervalMs() { return reconcilerIntervalMs; }
    public void setReconcilerIntervalMs(long reconcilerIntervalMs) { this.reconcilerIntervalMs = reconcilerIntervalMs; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) { this.defaultTimeoutSeconds = defaultTimeoutSeconds; }

    public int getResultFetchMaxAttempts() { return resultFetchMaxAttempts; }
    public void setResultFetchMaxAttempts(int resultFetchMaxAttempts) { this.resultFetchMaxAttempts = resultFetchMaxAttempts; }

    public long getResultRetentionDeadlineSeconds() { return resultRetentionDeadlineSeconds; }
    public void setResultRetentionDeadlineSeconds(long resultRetentionDeadlineSeconds) { this.resultRetentionDeadlineSeconds = resultRetentionDeadlineSeconds; }

    public long getTerminalRetentionSeconds() { return terminalRetentionSeconds; }
    public void setTerminalRetentionSeconds(long terminalRetentionSeconds) { this.terminalRetentionSeconds = terminalRetentionSeconds; }

    public long getLaunchingStaleSeconds() { return launchingStaleSeconds; }
    public void setLaunchingStaleSeconds(long launchingStaleSeconds) { this.launchingStaleSeconds = launchingStaleSeconds; }

    public long getResumeLauncherLeaseSeconds() { return resumeLauncherLeaseSeconds; }
    public void setResumeLauncherLeaseSeconds(long resumeLauncherLeaseSeconds) {
        this.resumeLauncherLeaseSeconds = resumeLauncherLeaseSeconds;
    }

    public boolean isDurableRecoveryEnabled() { return durableRecoveryEnabled; }
    public void setDurableRecoveryEnabled(boolean durableRecoveryEnabled) {
        this.durableRecoveryEnabled = durableRecoveryEnabled;
    }

    public int getContinuationMaxConsecutivePollFailures() {
        return continuationMaxConsecutivePollFailures;
    }
    public void setContinuationMaxConsecutivePollFailures(int continuationMaxConsecutivePollFailures) {
        this.continuationMaxConsecutivePollFailures = continuationMaxConsecutivePollFailures;
    }

}
