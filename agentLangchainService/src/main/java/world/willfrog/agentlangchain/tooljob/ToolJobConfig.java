package world.willfrog.agentlangchain.tooljob;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.tool-job")
public class ToolJobConfig {

    /** Fast-path timeout in ms. Jobs completing within this window return synchronously. */
    private long fastPathMs = 1500;

    /** Interval between reconciler poll rounds. */
    private long reconcilerIntervalMs = 5000;

    /** Interval between individual sandbox status checks within a poll round. */
    private long pollIntervalMs = 1000;

    /** Default sandbox timeout in seconds (used when tool doesn't specify). */
    private int defaultTimeoutSeconds = 300;

    /** Max attempts to fetch result after terminal status confirmed. */
    private int resultFetchMaxAttempts = 10;

    /** Max total time to retain result retry state before writing RESULT_LOST. */
    private long resultRetentionDeadlineSeconds = 600;

    /** Extra time after timeout before the job is considered abandoned. */
    private long terminalRetentionSeconds = 300;

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
}
