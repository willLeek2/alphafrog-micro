package world.willfrog.agent.platform.storage;

/**
 * D04 存储根可达性失败信号（W5 task #105）。
 *
 * <p>当统一存储门面 {@link AgentStoragePaths} 校验某个存储根不可达
 * （目录不存在且无法创建、存在但不可写、或被普通文件占位）时抛出，
 * 替代「静默写到错误位置」的行为（D04 §4.3：不可达 → 明确失败信号）。
 *
 * <p>异常消息恒包含：配置键名 + 解析后的绝对路径 + 失败原因，便于运维定位
 * 是挂载缺失、权限不足还是键配置错误。
 *
 * @author ccqwen
 */
public class StorageRootUnavailableException extends RuntimeException {

    public StorageRootUnavailableException(String message) {
        super(message);
    }

    public StorageRootUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
