package world.willfrog.agent.platform.exception;

/**
 * 长工具转后台时的真正失败：名额过户冲突，或数据库没接受「等待长工具」写入。
 *
 * <p>这不是挂起信号。抛出后工作线程不能释放，否则任务和名额都无人负责。</p>
 */
public final class ToolJobTransferException extends IllegalStateException {

    public ToolJobTransferException(String message) {
        super(message);
    }
}
