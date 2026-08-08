package world.willfrog.agent.tools.finance;

/**
 * 解析快照批量保存失败时抛出的专用异常。
 */
public class FinanceMethodResolutionSinkException extends RuntimeException {

    public FinanceMethodResolutionSinkException(String message) {
        super(message);
    }

    public FinanceMethodResolutionSinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
