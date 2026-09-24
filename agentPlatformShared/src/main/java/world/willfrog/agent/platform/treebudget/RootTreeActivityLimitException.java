package world.willfrog.agent.platform.treebudget;

/** 根调用树当前没有可用的节点或外部等待额度。 */
public class RootTreeActivityLimitException extends IllegalStateException {
    public RootTreeActivityLimitException(String kind, String rootRunId) {
        super("ROOT_TREE_ACTIVITY_LIMIT:" + kind + ":" + rootRunId);
    }
}
