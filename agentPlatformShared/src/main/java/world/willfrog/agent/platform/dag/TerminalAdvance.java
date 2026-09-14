package world.willfrog.agent.platform.dag;

/**
 * atomicTerminalLost 的返回类型。
 * newNodeVersion &gt; 0 表示 RESULT_LOST 写入成功。
 */
public record TerminalAdvance(long newNodeVersion) {
}
