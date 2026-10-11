package world.willfrog.agentlangchain.acceptance;

/**
 * 带夹具编号的 Run 在跑的过程中说不下去时抛出的异常。
 *
 * <p>脚本用完、夹具在跑的中途失效、调用身份认不出来，都要当场停住。脚本 {@code for} 没点名的
 * 调用走该阶段真实模型，不走这条异常。本该发预录却领不到回合、或者必答声明到终态没发生，
 * 仍按失败收场，不能拿真模型把这次算成预录成功。</p>
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

    /** 造一个拒绝：夹具这条路上的调用方都这么写，错误码与说明的拼接只有一处。 */
    public static AcceptanceFixtureExecutionException refuse(String code, String detail) {
        return new AcceptanceFixtureExecutionException(code, detail);
    }

    /** 稳定的错误码：同一类拒绝在日志、节点失败原因与验收证据里是同一个值。 */
    public String code() {
        return code;
    }
}
