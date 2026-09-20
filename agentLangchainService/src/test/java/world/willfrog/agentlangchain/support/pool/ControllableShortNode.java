package world.willfrog.agentlangchain.support.pool;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可控短节点：验收用的确定性节点样本。
 *
 * <p>默认每个节点跑 200 毫秒，结束方式可以预先指定，用来制造三种结束方式各一条证据。它不做任何真实模型或工具
 * 调用，也不碰数据库：只负责「确定性地占住一个执行分段一段时间，然后给出一个结果」。</p>
 *
 * <p>每个节点的执行次数会记下来，验收时用它证明「同一个身份只被执行了预期次数」，例如首次领取竞争里
 * 两个 Worker 都去领，只有一个真的跑到这里。</p>
 */
public class ControllableShortNode {

    /** 验收里冻结的短节点时长。 */
    public static final Duration FROZEN_DURATION = Duration.ofMillis(200);

    private final Duration duration;
    private final SegmentEnding ending;
    private final Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();

    public ControllableShortNode() {
        this(FROZEN_DURATION, SegmentEnding.NORMAL_RESULT);
    }

    public ControllableShortNode(Duration duration, SegmentEnding ending) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("节点时长不能为负");
        }
        this.duration = duration;
        this.ending = ending;
    }

    /** 跑一个节点：先占住这段时间，再按预定结束方式返回。 */
    public ShortNodeRun run(String nodeId) throws InterruptedException {
        long startedAt = System.nanoTime();
        executions.computeIfAbsent(nodeId, ignored -> new AtomicInteger()).incrementAndGet();
        Thread.sleep(duration.toMillis());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        String payload = "{\"nodeId\":\"" + nodeId + "\",\"ending\":\"" + ending.name() + "\"}";
        return new ShortNodeRun(nodeId, payload, ending, elapsedMillis);
    }

    /** 这个节点被真正执行过几次。 */
    public int executions(String nodeId) {
        AtomicInteger counter = executions.get(nodeId);
        return counter == null ? 0 : counter.get();
    }

    /** 所有节点一共执行了几次。 */
    public int totalExecutions() {
        return executions.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public Duration duration() {
        return duration;
    }

    public SegmentEnding ending() {
        return ending;
    }
}
