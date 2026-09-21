package world.willfrog.agentlangchain.acceptance;

/**
 * 带夹具编号的 Run 在跑的过程中说不下去时抛出的异常。
 *
 * <p>这类 Run 只认脚本里的那一份模型回复。脚本用完、夹具在跑的中途失效、消费位置找不回来，
 * 都要当场停住：这里没有「换成真实模型接着跑」那条退路——静默往下跑会让一次本该失败的验收
 * 看起来像跑过了，而验收最不能有的结果就是这个。</p>
 *
 * <p>消息以稳定的错误码开头（形如 {@code acceptance_fixture_xxx: 说明}）。节点失败原因、
 * 事件与验收证据直接引用这一段原文，所以改文案时错误码那一截要原样保留。</p>
 */
public class AcceptanceFixtureExecutionException extends RuntimeException {

    private final String code;

    public AcceptanceFixtureExecutionException(String code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }

    /** 稳定的错误码：同一类拒绝在日志、节点失败原因与验收证据里是同一个值。 */
    public String code() {
        return code;
    }
}
