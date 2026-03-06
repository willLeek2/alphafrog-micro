package world.willfrog.agent.workflow;

/**
 * DAG 构建校验异常（如循环依赖）。
 */
public class DagValidationException extends RuntimeException {

    public DagValidationException(String message) {
        super(message);
    }

    public DagValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
