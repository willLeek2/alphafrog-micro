package world.willfrog.agentlangchain.acceptance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次模型调用的稳定身份：这条 Run 上的哪一次调用，跨进程重启也是同一个值。
 *
 * <p>夹具脚本不再按「第几次调用」发回合，而是按调用身份发：脚本的每个回合用 {@code for} 声明
 * 它回答哪一次调用（见 {@link FrozenModelScript}），执行层每次调用模型时先把自己的身份报上来，
 * 由 {@link FixtureCallStore} 认领那个回合。这样两件事同时成立：同一个进程里两个节点并行要回复，
 * 各自拿到自己声明的那一份，与线程先后无关；进程重启后重做同一段，报上来的身份不变，拿到的还是
 * 原来那一份，不会跳到下一回合。</p>
 *
 * <p>身份由「阶段 + 一组域字段」组成。阶段是 {@link Stage}；域字段按阶段不同：</p>
 * <ul>
 *   <li>{@link Stage#PLANNING}：{@code planAttempt}（第几次规划尝试）、{@code planPhase}
 *       （{@code strategy} 或 {@code todos}）。</li>
 *   <li>{@link Stage#NODE}：{@code planGeneration}、{@code nodeId}、{@code nodeAttempt}、
 *       {@code segmentSequence}、{@code modelTurn}（这一段里的第几次模型回合）。</li>
 *   <li>{@link Stage#ANSWER}：没有额外字段（一条 Run 的计划代际内只会写一次答案）。</li>
 *   <li>{@link Stage#JUDGE}：{@code decisionIndex}（这一条 Run 上第几次图判定）。</li>
 * </ul>
 *
 * <p>{@code planGeneration} 是所有阶段都带上的：同一条 Run 重新规划之后，新的规划、节点与写答案
 * 都是新的一次调用，旧身份不该被复用。</p>
 *
 * <p>域字段的值一律是字符串（写进库、进日志、比对声明都用同一份写法）；组装时按字段名排序，
 * 所以同一组字段无论怎么传进来，落库的文本都是同一个。</p>
 */
public record FixtureCallIdentity(String runId, Stage stage, Map<String, String> scope) {

    /** 调用发生在哪一段。声明里写 {@code stage}，取值就是这里的名字。 */
    public enum Stage {
        PLANNING,
        NODE,
        ANSWER,
        JUDGE
    }

    /** 身份里的固定字段名：声明里只许写这些，写错当场拒绝，不留「拼错了所以永远不命中」的口子。 */
    public static final Set<String> SCOPE_KEYS = Set.of(
            "planGeneration", "planAttempt", "planPhase", "nodeId", "nodeAttempt",
            "segmentSequence", "modelTurn", "decisionIndex");

    public FixtureCallIdentity {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("调用身份必须带 runId");
        }
        if (stage == null) {
            throw new IllegalArgumentException("调用身份必须带阶段：" + runId);
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        if (scope != null) {
            List<String> keys = new ArrayList<>(scope.keySet());
            keys.sort(Comparator.naturalOrder());
            for (String key : keys) {
                String value = scope.get(key);
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("调用身份的域字段名不能为空：" + runId);
                }
                if (!SCOPE_KEYS.contains(key)) {
                    throw new IllegalArgumentException("调用身份里有认不出的域字段 " + key + "：" + runId);
                }
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("调用身份的域字段 " + key + " 没有值：" + runId);
                }
                normalized.put(key, value);
            }
        }
        scope = Map.copyOf(normalized);
    }

    /** 规划那两次调用（先策略、再待办清单）。 */
    public static FixtureCallIdentity planning(String runId, int planGeneration, int planAttempt, String phase) {
        return new FixtureCallIdentity(runId, Stage.PLANNING, Map.of(
                "planGeneration", Integer.toString(planGeneration),
                "planAttempt", Integer.toString(planAttempt),
                "planPhase", phase));
    }

    /** 一个节点分段的第几次模型回合。 */
    public static FixtureCallIdentity nodeSegment(String runId,
                                                  int planGeneration,
                                                  String nodeId,
                                                  int nodeAttempt,
                                                  int segmentSequence,
                                                  int modelTurn) {
        return new FixtureCallIdentity(runId, Stage.NODE, Map.of(
                "planGeneration", Integer.toString(planGeneration),
                "nodeId", nodeId,
                "nodeAttempt", Integer.toString(nodeAttempt),
                "segmentSequence", Integer.toString(segmentSequence),
                "modelTurn", Integer.toString(modelTurn)));
    }

    /** 写最终答案这一次。 */
    public static FixtureCallIdentity answer(String runId, int planGeneration) {
        return new FixtureCallIdentity(runId, Stage.ANSWER, Map.of(
                "planGeneration", Integer.toString(planGeneration)));
    }

    /** 图判定这一次。 */
    public static FixtureCallIdentity judge(String runId, int planGeneration, int decisionIndex) {
        return new FixtureCallIdentity(runId, Stage.JUDGE, Map.of(
                "planGeneration", Integer.toString(planGeneration),
                "decisionIndex", Integer.toString(decisionIndex)));
    }

    /** 落库、日志与比对声明的写法：{@code stage=node;nodeId=n1;planGeneration=0;...}，字段名按字母序。 */
    public String describe() {
        StringBuilder text = new StringBuilder("stage=").append(stage.name().toLowerCase());
        scope.keySet().stream().sorted().forEach(key -> text.append(';').append(key).append('=').append(scope.get(key)));
        return text.toString();
    }

    /**
     * 这份身份是不是落在这条声明里。
     *
     * <p>声明里写了的字段都要与身份相同；没写的字段不参与判断（写 {@code nodeId} 不写
     * {@code segmentSequence}，就是「这个节点的每一段都算」）。阶段必须写，且必须相同。</p>
     */
    public boolean matches(String declaredStage, Map<String, String> declaredScope) {
        if (declaredStage == null || !declaredStage.equalsIgnoreCase(stage.name())) {
            return false;
        }
        if (declaredScope == null || declaredScope.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> entry : declaredScope.entrySet()) {
            if (!entry.getValue().equals(scope.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
