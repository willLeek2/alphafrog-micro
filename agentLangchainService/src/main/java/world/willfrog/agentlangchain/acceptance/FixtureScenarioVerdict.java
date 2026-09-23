package world.willfrog.agentlangchain.acceptance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次验收的脚本核对：这次要求发生的模型调用与点名，是不是都真的发生了。
 *
 * <p>少了回合的时候，前面那几条回复照样能让 Run 走到终态——某个节点的模型回合根本没发生、
 * 连续等待组被跳过、提前写了最终答案——脚本尾部没用上的部分会被静默丢掉，一次本来没跑完整路径的
 * 验收看起来就是通过的。所以核对要按「声明」算：脚本里没有标 {@code optional} 的回合都是必答，
 * 必答回合必须恰好被认领过一次。</p>
 *
 * <p>放行策略那一路同理，而且要核两件事。规则写错了字段名或序号时一声不响，一条成员都打不中；
 * 就算打中了，点名的动作也可能根本没有落到那条成员身上（一条要求压住成员的规则，遇到当场就出结果的
 * 成员时没有可等的东西）。两种情况下那次验收都看起来像跑完了，所以「一条都没打中」与「打中了、
 * 动作没生效」都要在结论里分开点名。</p>
 *
 * <p>结果是纯计算：给一份脚本、一份规则清单、这条 Run 已经认领的回合号与规则记录，
 * 得出结论、没发生的必答声明、没打中的规则、动作没生效的规则、以及被领走的声明清单。读库与落库
 * 在外面（{@link FixtureCallStore}），这里只做判断，好照着例子核对。</p>
 *
 * @param verdict       结论：{@link #COMPLETE} 或 {@link #SCRIPT_INCOMPLETE}
 * @param missing       没发生的必答声明（原文写法），按回合序号排
 * @param missingRules  一次都没打中任何成员的规则（写法），按规则序号排
 * @param unappliedRules 打中了成员、但被点名的动作没有落到它身上的规则（写法），按规则序号排
 * @param overHitRules   已经绑过一条成员、后来又打中别的成员的规则（写法），按规则序号排
 * @param consumed      被领走的声明与领走它的调用身份，按回合序号排
 */
public record FixtureScenarioVerdict(String verdict,
                                     List<String> missing,
                                     List<String> missingRules,
                                     List<String> unappliedRules,
                                     List<String> overHitRules,
                                     List<String> consumed) {

    /** 必答声明都发生过、点名的规则都打中过成员、点名的动作也都真的生效了、也没有越界命中：要求的调用路径跑全了。 */
    public static final String COMPLETE = "complete";
    /** 有必答声明没发生、点名的规则一条都没打中、点名的动作没有生效、或者规则越界打中了别的成员：这一次验收不能算通过。 */
    public static final String SCRIPT_INCOMPLETE = "script_incomplete";

    public static FixtureScenarioVerdict evaluate(FrozenModelScript script, Set<Integer> claimedTurns) {
        return evaluate(script.declarations(), List.of(), claimedTurns, List.of());
    }

    /** 与上面同一个核对，只是拿声明清单算：终态核对读的是当初落库的声明快照，不是现在的脚本。 */
    public static FixtureScenarioVerdict evaluate(List<FrozenModelScript.TurnDeclaration> declarations,
                                                  Set<Integer> claimedTurns) {
        return evaluate(declarations, List.of(), claimedTurns, List.of());
    }

    /**
     * 脚本与策略两路一起核对。
     *
     * @param declarations 这次要求的回复声明（落库的声明快照）
     * @param ruleFacts    这次要求点名的规则（落库的策略快照）
     * @param claimedTurns 真的被领走的回合号
     * @param ruleHits     每条规则的落库记录（打中了谁、动作生效了没有）
     */
    public static FixtureScenarioVerdict evaluate(List<FrozenModelScript.TurnDeclaration> declarations,
                                                  List<FixtureRuleHitStore.RuleFact> ruleFacts,
                                                  Set<Integer> claimedTurns,
                                                  List<FixtureRuleHitStore.RuleHit> ruleHits) {
        Set<Integer> claimed = claimedTurns == null ? Set.of() : new LinkedHashSet<>(claimedTurns);
        Map<Integer, FixtureRuleHitStore.RuleHit> hits = new LinkedHashMap<>();
        if (ruleHits != null) {
            for (FixtureRuleHitStore.RuleHit hit : ruleHits) {
                hits.put(hit.ruleIndex(), hit);
            }
        }
        List<String> missing = new ArrayList<>();
        List<String> missingRules = new ArrayList<>();
        List<String> unappliedRules = new ArrayList<>();
        List<String> overHitRules = new ArrayList<>();
        List<String> consumed = new ArrayList<>();
        for (FrozenModelScript.TurnDeclaration declaration : declarations) {
            if (claimed.contains(declaration.turnIndex())) {
                consumed.add(declaration.describe());
            } else if (!declaration.optional()) {
                missing.add(declaration.describe());
            }
        }
        for (FixtureRuleHitStore.RuleFact fact : ruleFacts) {
            FixtureRuleHitStore.RuleHit hit = hits.get(fact.index());
            if (hit == null) {
                missingRules.add(fact.describe());
                continue;
            }
            if (!hit.applied()) {
                unappliedRules.add(fact.describe() + "：点名的是 " + hit.target().describe()
                        + "，动作" + (hit.settled() ? "结果是 " + hit.actionOutcome()
                                : "还没落地")
                        + (hit.actionDetail() == null || hit.actionDetail().isBlank()
                                ? "" : "（" + hit.actionDetail() + "）"));
            }
            if (hit.overHit()) {
                overHitRules.add(fact.describe() + "：" + (hit.actionDetail() == null
                        ? FixtureRuleHitStore.OVER_HIT_MARKER
                        : hit.actionDetail()));
            }
        }
        boolean everythingHappened = missing.isEmpty() && missingRules.isEmpty()
                && unappliedRules.isEmpty() && overHitRules.isEmpty();
        return new FixtureScenarioVerdict(everythingHappened ? COMPLETE : SCRIPT_INCOMPLETE,
                List.copyOf(missing), List.copyOf(missingRules), List.copyOf(unappliedRules),
                List.copyOf(overHitRules), List.copyOf(consumed));
    }

    /** 没发生的那几件事写成一行的写法：落库的 detail 与日志都用它。 */
    public String describeMissing() {
        List<String> parts = new ArrayList<>();
        if (!missing.isEmpty()) {
            parts.add("脚本声明为必答、实际没有发生的回合：" + String.join("；", missing));
        }
        if (!missingRules.isEmpty()) {
            parts.add("放行策略里点名的、一次都没打中任何成员的规则：" + String.join("；", missingRules));
        }
        if (!unappliedRules.isEmpty()) {
            parts.add("放行策略里点名打中了成员、但动作没有落到它身上的规则："
                    + String.join("；", unappliedRules));
        }
        if (!overHitRules.isEmpty()) {
            parts.add("放行策略里已经绑过一条成员、后来又打中别的成员的规则："
                    + String.join("；", overHitRules));
        }
        return parts.isEmpty() ? null : String.join(" | ", parts);
    }
}
