package world.willfrog.agent.platform.wait;

import world.willfrog.agent.platform.exception.AgentRunControlSignal;

/**
 * 「这个成员的后台作业已经交出去了」的信号。
 *
 * <p>它不是失败：沙箱任务已经建好，或者正在建的途中，只是这一次调用不再等它。它和旧路径上的
 * {@code ExternalToolJobPendingException} 是同一个意思，落点不同：旧路径把事实写进 Run 级进度记录，
 * 这里把事实装进派发证明，由派发器写进成员行。</p>
 *
 * <p>工具层抛出之后，沿途的通用异常处理都要原样上抛。一旦被改写成工具失败文本，模型会以为这次调用
 * 已经完成，而库里还没有记下这次后台作业。</p>
 */
public class WaitGroupMemberPendingException extends RuntimeException implements AgentRunControlSignal {

    private final WaitMemberDispatchProof proof;

    public WaitGroupMemberPendingException(WaitMemberDispatchProof proof, String message) {
        super(message);
        if (proof == null) {
            throw new IllegalArgumentException("派发证明不能为空");
        }
        this.proof = proof;
    }

    public WaitMemberDispatchProof getProof() {
        return proof;
    }

    public String getOperationId() {
        return proof.operationId();
    }

    /** 后台任务编号；可能为空，表示建任务的结果还没被证实。 */
    public String getTaskId() {
        return proof.taskId();
    }
}
