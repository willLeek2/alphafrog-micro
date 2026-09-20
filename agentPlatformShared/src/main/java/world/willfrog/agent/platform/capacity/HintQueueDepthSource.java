package world.willfrog.agent.platform.capacity;

/**
 * 内存提示队列的读数来源：队列里现在有几个元素、还有几个已经预留但还没入队。
 *
 * <p>提示队列是可丢、可重复的唤醒通道，不是事实源，所以这两个数字只用来观测与背压，不用来判断
 * 「有没有活」。真正的活以数据库里可运行的工作项为准。</p>
 */
public interface HintQueueDepthSource {

    /** 提示队列里的元素个数。 */
    int hintQueueDepth();

    /** 已经预留（拿到名额或位置）但还没入队的数量。 */
    int reservedNotEnqueued();

    /** 两个数都给不出来的实现用这个：一律按 0 报，绝不假装有值。 */
    static HintQueueDepthSource empty() {
        return new HintQueueDepthSource() {
            @Override
            public int hintQueueDepth() {
                return 0;
            }

            @Override
            public int reservedNotEnqueued() {
                return 0;
            }
        };
    }
}
