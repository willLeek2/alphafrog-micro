package world.willfrog.agent.platform.dag;

/**
 * cancelFrontierAndChildrenCTE 的返回类型。
 * success=true 且 frontierRows==1 表示 Phase A 成功。
 */
public record CancelResult(boolean success, int frontierRows, int childMarkedRows) {
}
