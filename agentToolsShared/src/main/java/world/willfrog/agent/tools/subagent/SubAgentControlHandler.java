package world.willfrog.agent.tools.subagent;

import java.util.Map;

/**
 * 子代理控制工具的跨模块执行接口。
 *
 * <p>{@code agentToolsShared} 只声明稳定的工具路由，不依赖 LangChain4j。真正的异步执行、
 * 持久事件和等待语义由 {@code agentLangchainService} 提供。这样工具注册表和 Router 可以
 * 保持单一声明源，同时避免把具体模型类型泄漏到共享工具模块。</p>
 */
public interface SubAgentControlHandler {

    /** 接受一个子代理目标并返回结构化 JSON。 */
    String spawn(Map<String, Object> params);

    /** 等待一个或多个子代理并返回结构化 JSON。 */
    String waitFor(Map<String, Object> params);
}
