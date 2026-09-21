package world.willfrog.agent.platform.idempotency;

/**
 * 同一个幂等键配上了不同的请求内容。
 *
 * <p>这是失败关闭信号：既不读回原来那条 Run（内容不一样，读回等于答非所问），也不新建第二条
 * （同一个键对应两条 Run 会让「重复提交」失去意义）。调用方按业务错误返回给客户端。</p>
 */
public class RunIdempotencyConflictException extends IllegalStateException {

    public RunIdempotencyConflictException(String message) {
        super(message);
    }
}
