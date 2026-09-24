package world.willfrog.agentlangchain.control;

/**
 * 旧版本 Run 的交接口：把一条已经不归任何人服务的旧 Run 交给旧执行路径继续跑。
 *
 * <p>只有已经握着这条 Run 服务所有权的那一方才该调用它。接口放在这里是因为旧的启动恢复协议
 * 不对外公开，交接口与它在同一个包里，不必为了调用它把协议本身公开出去。</p>
 */
public interface LegacyRunHandoff {

    /**
     * 把这条 Run 交给旧路径继续。
     *
     * @return 确实领取成功并交付返回 true；Run 读不到、已经结束、旧路径没启用、
     *         或者领取条件不成立（别人先领走了）返回 false
     */
    boolean handOff(String runId);
}
