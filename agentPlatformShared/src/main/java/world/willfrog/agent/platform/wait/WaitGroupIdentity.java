package world.willfrog.agent.platform.wait;

import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

/**
 * 等待组的稳定身份：节点五字段身份加模型回合。
 *
 * <p>模型回合是指这个节点在这一段执行里的第几次模型回复。同一个分段的同一次回复里并列发出的工具请求
 * 属于同一个组；下一段执行会带来新的分段序号，所以连续两个等待组天然是两个身份，不会互相覆盖。</p>
 *
 * <p>这六个字段一组上有数据库唯一约束，保证同一次模型回合的整组请求只会被保存一次。</p>
 */
public record WaitGroupIdentity(NodeWorkItemIdentity segment, int modelTurn) {

    public WaitGroupIdentity {
        if (segment == null) {
            throw new IllegalArgumentException("等待组必须挂在某个节点分段上");
        }
        if (modelTurn < 0) {
            throw new IllegalArgumentException("模型回合不能是负数：" + modelTurn);
        }
    }

    /** 日志与拒绝事实里的紧凑写法：分段身份加模型回合。 */
    public String describe() {
        return segment.describe() + "/t" + modelTurn;
    }

    public String runId() {
        return segment.runId();
    }

    public int planGeneration() {
        return segment.planGeneration();
    }

    public String nodeId() {
        return segment.nodeId();
    }

    public int nodeAttempt() {
        return segment.nodeAttempt();
    }

    public int segmentSequence() {
        return segment.segmentSequence();
    }
}
