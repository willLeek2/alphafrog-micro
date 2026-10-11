package world.willfrog.agent.platform.wait;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 等待成员的稳定身份。
 *
 * <p>身份在整组事务里一次算好、立即落库，之后重试只读库里的值，不再重新生成：只有这样才能保证
 * 「同一个成员身份只结束一次」。生成规则分两种：</p>
 *
 * <ul>
 *   <li>模型给了工具调用身份：去掉首尾空白后原样使用。同一个组里不会出现两个相同的调用身份。</li>
 *   <li>模型没给身份：按「完整等待组身份 + 原始序号」算一个定长摘要，带 {@value #DERIVED_PREFIX} 前缀。
 *       它是确定性的，同一组同一序号重算出来永远一样，不依赖时钟、随机数或进程。</li>
 * </ul>
 *
 * <p>派生身份走摘要而不是拼接，是因为拼接的身份长度会被组身份拖着走：节点身份本身可以有 256 个字符，
 * 拼出来就超过库里 256 的上限，正常的整组请求会因此写不进去。摘要固定 64 位十六进制，加上前缀
 * 一共 {@value #DERIVED_IDENTITY_LENGTH} 个字符，与输入多长无关。摘要里带上组身份的全部字段与成员序号，
 * 字段按「名字 + 长度 + 内容」逐段写进去，所以「哪个字段到哪儿结束」没有歧义，不同的组不会算出同一个值。</p>
 *
 * <p>不同节点、连续等待组里出现相同的原始工具调用身份不算冲突：唯一范围限定在同一个组内部，
 * 跨组和跨段各自独立。</p>
 */
public final class WaitMemberIdentity {

    /** 派生身份的家族前缀，便于在库里一眼看出这个身份不是模型给的。版本号跟在它后面。 */
    public static final String DERIVED_FAMILY_PREFIX = "derived:";

    /** 派生身份的完整前缀；换个算法就换版本号，老身份仍然按老规则可读可查。 */
    public static final String DERIVED_PREFIX = DERIVED_FAMILY_PREFIX + "v1:";

    /** 库里成员身份列的长度上限，算出来的身份超过它就失败关闭，而不是让数据库拒绝。 */
    public static final int MAX_LENGTH = 256;

    /** 派生身份的固定长度：前缀加 64 位十六进制。 */
    public static final int DERIVED_IDENTITY_LENGTH = DERIVED_PREFIX.length() + 64;

    private WaitMemberIdentity() {
    }

    /**
     * 算出一个成员的稳定身份。
     *
     * @param toolCallId 模型回复里给出的工具调用身份，可能为空
     * @param group      这个成员所属的等待组身份
     * @param memberSeq  成员在整组请求里的原始序号
     */
    public static String stableIdentity(String toolCallId, WaitGroupIdentity group, int memberSeq) {
        if (group == null) {
            throw new IllegalArgumentException("成员必须属于某个等待组");
        }
        if (memberSeq < 0) {
            throw new IllegalArgumentException("成员原始序号不能是负数：" + memberSeq);
        }
        String normalizedToolCallId = toolCallId == null ? null : toolCallId.strip();
        if (normalizedToolCallId != null && !normalizedToolCallId.isEmpty()) {
            if (normalizedToolCallId.length() > MAX_LENGTH) {
                throw new IllegalArgumentException("成员身份超过库里长度上限 " + MAX_LENGTH + "："
                        + normalizedToolCallId.substring(0, 32) + "...");
            }
            return normalizedToolCallId;
        }
        return DERIVED_PREFIX + digest(group, memberSeq);
    }

    /** 这个身份是不是派生出来的（模型没给工具调用身份）。 */
    public static boolean isDerived(String identity) {
        return identity != null && identity.startsWith(DERIVED_FAMILY_PREFIX);
    }

    /**
     * 组身份与序号的定长摘要。
     *
     * <p>字段逐个写成「名字 + 冒号 + 内容长度 + 冒号 + 内容 + 分号」：内容里出现什么字符都不会
     * 造成两段内容连起来读成另一种切分，所以两组不同的输入不会算出同一个摘要。</p>
     */
    private static String digest(WaitGroupIdentity group, int memberSeq) {
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, "run_id", group.runId());
        appendField(canonical, "plan_generation", String.valueOf(group.planGeneration()));
        appendField(canonical, "node_id", group.nodeId());
        appendField(canonical, "node_attempt", String.valueOf(group.nodeAttempt()));
        appendField(canonical, "segment_sequence", String.valueOf(group.segmentSequence()));
        appendField(canonical, "model_turn", String.valueOf(group.modelTurn()));
        appendField(canonical, "member_seq", String.valueOf(memberSeq));
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境没有 SHA-256", e);
        }
    }

    private static void appendField(StringBuilder target, String name, String value) {
        String text = value == null ? "" : value;
        target.append(name).append(':').append(text.length()).append(':').append(text).append(';');
    }
}
