package world.willfrog.agent.platform.workitem;

/**
 * 工作项上除「计划代际」之外的三个版本取值，加上领取代际一起构成四类版本。
 *
 * <p>计划代际在 {@link NodeWorkItemIdentity} 上（它是不可变身份的一部分），这里放的是这条工作项上
 * 会随执行推进而变化的三项：</p>
 * <ul>
 *   <li>{@code contextVersion} 上下文版本：这条工作项创建时读取的精确上下文版本。节点侧只校验
 *       「提交里的值等于工作项里的值」，不拿它跟 Run 的全局最新值比较。</li>
 *   <li>{@code runControlVersion} 控制版本：暂停、恢复、取消、计划失效推进到第几版。</li>
 *   <li>{@code claimEpoch} 领取代际：领取成功后原子加一的执行权令牌。</li>
 * </ul>
 */
public record NodeWorkItemVersions(long contextVersion, long runControlVersion, int claimEpoch) {

    public NodeWorkItemVersions {
        if (contextVersion < 0 || runControlVersion < 0) {
            throw new IllegalArgumentException(
                    "上下文版本与控制版本都不能是负数：" + contextVersion + "/" + runControlVersion);
        }
        if (claimEpoch < 0) {
            throw new IllegalArgumentException("领取代际不能是负数：" + claimEpoch);
        }
    }

    /** 由一行工作项取三个版本取值。 */
    public static NodeWorkItemVersions of(NodeWorkItem item) {
        return new NodeWorkItemVersions(
                item.getContextVersion(), item.getRunControlVersion(), item.getClaimEpoch());
    }

    /** 日志与拒绝事实里的紧凑写法。 */
    public String describe() {
        return "ctx=" + contextVersion + ",ctrl=" + runControlVersion + ",epoch=" + claimEpoch;
    }
}
