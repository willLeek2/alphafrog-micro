package world.willfrog.agent.platform.dataanalysis;

/**
 * executeQuery 会话串行守卫失败。只在抢占 PREPARING 锚点的同一事务里抛出。
 *
 * <p>{@code SESSION_QUERY_IN_PROGRESS} 表示同一用户已有另一条未清空的 executeQuery 锚点，
 * 调用方可稍后重试。{@code SESSION_USER_ID_MISSING} 表示当前 Run 没有 user_id，
 * 无法按用户加锁，属于数据问题，不可重试。</p>
 */
public class SessionQueryAdmissionException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public SessionQueryAdmissionException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
