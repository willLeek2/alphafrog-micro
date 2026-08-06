package world.willfrog.agent.tools.python;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Java 侧数据分析容量账本的配置与确定性分类器。
 *
 * <p>它在调用 Python Sandbox 之前生效，与 Sandbox 内部的 worker/容器伸缩是两层独立控制：
 * 本账本拒绝后，请求不会到达 Sandbox，也不会触发 Sandbox 扩容。配置前缀固定为
 * {@code alphafrog.data-analysis.capacity}，避免与多 owner 的 {@code AgentLlmProperties}
 * 混在一起。</p>
 */
@Data
@ConfigurationProperties(prefix = "alphafrog.data-analysis.capacity")
public class DataAnalysisCapacityProperties {

    /** 单个 Java 实例允许持有的总 capacity units；STANDARD=1，HEAVY=3。 */
    private int maxUnits = 4;

    /** 所有资源档位合计的 pre-create 准入数上限，PREPARING 也必须计入。 */
    private int maxActive = 2;

    /** HEAVY 的 pre-create 准入数上限，防止多个大任务先创建容器后才被拒绝。 */
    private int maxHeavyActive = 1;

    /** 单任务行数硬上限；超过后返回 DATA_ANALYSIS_TASK_TOO_LARGE，不进入排队。 */
    private long maxRowsPerTask = 600_000L;

    /** 单任务字节数硬上限；超过后返回 DATA_ANALYSIS_TASK_TOO_LARGE，不进入排队。 */
    private long maxBytesPerTask = 512L * 1024L * 1024L;

    /** STANDARD 最大行数；行数、字节和重操作提示必须同时满足才属于 STANDARD。 */
    private long standardRowsMax = 200_000L;

    /** STANDARD 最大字节数；任一标准阈值不满足但未越硬上限时归为 HEAVY。 */
    private long standardBytesMax = 32L * 1024L * 1024L;

    /** STANDARD 任务传给 Sandbox 的内存上限（字节）。 */
    private long standardMemoryLimitBytes = 512L * 1024L * 1024L;

    /** HEAVY 任务传给 Sandbox 的内存上限（字节）。 */
    private long heavyMemoryLimitBytes = 1536L * 1024L * 1024L;

    /**
     * 根据冻结的行数、字节预算和重操作提示确定资源档位。
     *
     * <p>规则必须是确定性的：行数不超过 {@code standardRowsMax}、字节不超过
     * {@code standardBytesMax} 且没有重操作提示时为 STANDARD；仍在单任务硬上限内但
     * 任一 STANDARD 条件不满足时为 HEAVY；越过硬上限时直接 REJECTED。调用链只能分类
     * 一次，后续 estimate、reservation、Sandbox request 与终态证明必须复用同一结果。</p>
     */
    public DataAnalysisResourceClassDecision classify(
            long estimatedRows, long estimatedBytes, java.util.List<String> heavyOperationHints) {
        if (estimatedRows > maxRowsPerTask || estimatedBytes > maxBytesPerTask) {
            return DataAnalysisResourceClassDecision.rejected(estimatedRows, estimatedBytes,
                    maxRowsPerTask, maxBytesPerTask);
        }
        boolean standardByRows = estimatedRows <= standardRowsMax;
        boolean standardByBytes = estimatedBytes <= standardBytesMax;
        boolean standardByHints = heavyOperationHints == null || heavyOperationHints.isEmpty();
        if (standardByRows && standardByBytes && standardByHints) {
            return DataAnalysisResourceClassDecision.standard(
                    standardMemoryLimitBytes, maxRowsPerTask, maxBytesPerTask);
        }
        return DataAnalysisResourceClassDecision.heavy(
                heavyMemoryLimitBytes, maxRowsPerTask, maxBytesPerTask);
    }

    /** 分类结果；同时冻结档位、unit 数、内存上限和诊断用硬上限。 */
    public record DataAnalysisResourceClassDecision(
            Outcome outcome,
            world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass resourceClass,
            int capacityUnits,
            long memoryLimitBytes,
            long rowsLimit,
            long bytesLimit) {

        public enum Outcome { ACCEPTED, REJECTED }

        public static DataAnalysisResourceClassDecision standard(
                long memoryLimitBytes, long rowsLimit, long bytesLimit) {
            return new DataAnalysisResourceClassDecision(
                    Outcome.ACCEPTED,
                    world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass.STANDARD,
                    world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass.STANDARD.defaultCapacityUnits(),
                    memoryLimitBytes,
                    rowsLimit,
                    bytesLimit);
        }

        public static DataAnalysisResourceClassDecision heavy(
                long memoryLimitBytes, long rowsLimit, long bytesLimit) {
            return new DataAnalysisResourceClassDecision(
                    Outcome.ACCEPTED,
                    world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass.HEAVY,
                    world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass.HEAVY.defaultCapacityUnits(),
                    memoryLimitBytes,
                    rowsLimit,
                    bytesLimit);
        }

        public static DataAnalysisResourceClassDecision rejected(
                long rowsLimit, long bytesLimit, long configuredRowsLimit, long configuredBytesLimit) {
            return new DataAnalysisResourceClassDecision(
                    Outcome.REJECTED,
                    null,
                    0,
                    0,
                    configuredRowsLimit,
                    configuredBytesLimit);
        }
    }
}
