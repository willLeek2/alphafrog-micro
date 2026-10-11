package world.willfrog.agent.platform.service;

/**
 * Nacos 已启用，但 agent-llm 的生效内容还没有写进本地缓存并加载到内存。
 *
 * <p>这个信号要求调用方失败关闭：不得用挂载目录里的种子文件，也不得用环境属性里的
 * {@code LEGACY} 默认值去冻结新 Run 的调度器版本。</p>
 */
public class AgentLlmHotConfigNotSyncedException extends IllegalStateException {

    public AgentLlmHotConfigNotSyncedException(String message) {
        super(message);
    }
}
