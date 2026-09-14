package world.willfrog.agent.platform.exception;

/**
 * 框架交界上的控制流信号：不是失败，请上层改走挂起、取消或暂停。
 *
 * <p>工具跑在 LangChain4j 的「模型 ↔ 工具」循环里，返回值会被当成工具结果送回模型，
 * 退出循环只能抛异常。这个接口把「该放线程 / 该停下来」的 throw 和真正失败分开。
 * 错误处理器只认这个家族，再原样抛回调用栈。</p>
 */
public interface AgentRunControlSignal {
}
