package world.willfrog.agentlangchain.acceptance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次验收的脚本核对：这次要求发生的模型调用，是不是都真的发生了。
 *
 * <p>少了回合的时候，前面那几条回复照样能让 Run 走到终态——某个节点的模型回合根本没发生、
 * 连续等待组被跳过、提前写了最终答案——脚本尾部没用上的部分会被静默丢掉，一次本来没跑完整路径的
 * 验收看起来就是通过的。所以核对要按「声明」算：脚本里没有标 {@code optional} 的回合都是必答，
 * 必答回合必须恰好被认领过一次。</p>
 *
 * <p>结果是纯计算：给一份脚本和这条 Run 已经认领的回合序号，得出结论、没发生的必答声明、以及
 * 被领走的声明清单。读库与落库在外面（{@link FixtureCallStore}），这里只做判断，好照着例子核对。</p>
 *
 * @param verdict     结论：{@link #COMPLETE} 或 {@link #SCRIPT_INCOMPLETE}
 * @param missing     没发生的必答声明（原文写法），按回合序号排
 * @param consumed    被领走的声明与领走它的调用身份，按回合序号排
 */
public record FixtureScenarioVerdict(String verdict, List<String> missing, List<String> consumed) {

    /** 必答声明都发生过：这次验收要求的调用路径跑全了。 */
    public static final String COMPLETE = "complete";
    /** 有必答声明没发生：这一次验收不能算通过。 */
    public static final String SCRIPT_INCOMPLETE = "script_incomplete";

    public static FixtureScenarioVerdict evaluate(FrozenModelScript script, Set<Integer> claimedTurns) {
        return evaluate(script.declarations(), claimedTurns);
    }

    /** 与上面同一个核对，只是拿声明清单算：终态核对读的是当初落库的声明快照，不是现在的脚本。 */
    public static FixtureScenarioVerdict evaluate(List<FrozenModelScript.TurnDeclaration> declarations,
                                                  Set<Integer> claimedTurns) {
        Set<Integer> claimed = claimedTurns == null ? Set.of() : new LinkedHashSet<>(claimedTurns);
        List<String> missing = new ArrayList<>();
        List<String> consumed = new ArrayList<>();
        for (FrozenModelScript.TurnDeclaration declaration : declarations) {
            if (claimed.contains(declaration.turnIndex())) {
                consumed.add(declaration.describe());
            } else if (!declaration.optional()) {
                missing.add(declaration.describe());
            }
        }
        return new FixtureScenarioVerdict(missing.isEmpty() ? COMPLETE : SCRIPT_INCOMPLETE, missing, consumed);
    }

    /** 没发生的必答声明写成一行的写法：落库的 detail 与日志都用它。 */
    public String describeMissing() {
        return missing.isEmpty()
                ? null
                : "脚本声明为必答、实际没有发生的回合：" + String.join("；", missing);
    }
}
