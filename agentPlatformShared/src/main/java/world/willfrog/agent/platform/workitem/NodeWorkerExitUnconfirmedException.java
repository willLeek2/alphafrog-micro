package world.willfrog.agent.platform.workitem;

/** 旧节点执行者是否已经退出尚未证实；保留占用并在下一轮恢复扫描重试。 */
public final class NodeWorkerExitUnconfirmedException extends IllegalStateException {
    public NodeWorkerExitUnconfirmedException(String message) {
        super(message);
    }
}
