package world.willfrog.agent.platform.dag;

/**
 * writePreparingStuck / writeRpcExhausted 的返回类型。
 * newNodeVersion &gt; 0 表示标记写入成功。
 */
public record ExhaustedAdvance(long newNodeVersion) {
}
