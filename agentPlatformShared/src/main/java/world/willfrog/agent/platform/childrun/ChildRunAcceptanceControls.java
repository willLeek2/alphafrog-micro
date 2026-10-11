package world.willfrog.agent.platform.childrun;

import com.fasterxml.jackson.databind.JsonNode;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitMember;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 受限父请求按完整工具成员身份，为指定子 Run 绑定结果控制行。
 * 身份覆盖节点分段和模型回合；其他节点或回合重复的成员序号不会误绑。普通请求没有绑定。
 */
public final class ChildRunAcceptanceControls {
    public static final String CONTEXT_FIELD = "childAcceptanceControls";
    private static final int MAX_BINDINGS = 12;
    private static final Set<String> BINDING_FIELDS = Set.of("for", "acceptanceControlId");
    private static final Set<String> SELECTOR_FIELDS = Set.of("planGeneration", "nodeId",
            "nodeAttempt", "segmentSequence", "modelTurn", "memberSeq", "toolCallId");

    private ChildRunAcceptanceControls() {
    }

    public record MemberIdentity(int planGeneration, String nodeId, int nodeAttempt,
                                 int segmentSequence, int modelTurn, int memberSeq,
                                 String toolCallId) {
        public MemberIdentity {
            if (planGeneration < 0 || nodeAttempt < 0 || segmentSequence < 0 || modelTurn < 0
                    || memberSeq < 0 || nodeId == null || nodeId.isBlank()
                    || toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("子 Run 控制目标的父工具成员身份不完整");
            }
        }
    }

    public record Binding(MemberIdentity member, String controlId) {
    }

    public static List<Binding> parse(JsonNode context) {
        if (context == null || !context.isObject()) {
            throw new IllegalArgumentException("父请求上下文必须是 JSON 对象");
        }
        JsonNode raw = context.get(CONTEXT_FIELD);
        if (raw == null || raw.isNull()) {
            return List.of();
        }
        if (!raw.isArray() || raw.size() > MAX_BINDINGS) {
            throw new IllegalArgumentException(CONTEXT_FIELD + " 必须是至多 " + MAX_BINDINGS + " 项的数组");
        }
        List<Binding> bindings = new ArrayList<>();
        Set<MemberIdentity> seenMembers = new LinkedHashSet<>();
        Set<String> seenControls = new LinkedHashSet<>();
        for (JsonNode row : raw) {
            requireFields(row, BINDING_FIELDS, "子 Run 控制绑定");
            JsonNode selector = row.get("for");
            requireFields(selector, SELECTOR_FIELDS, "子 Run 控制目标");
            MemberIdentity member = new MemberIdentity(
                    nonnegativeInt(selector, "planGeneration"),
                    nonblankText(selector, "nodeId"),
                    nonnegativeInt(selector, "nodeAttempt"),
                    nonnegativeInt(selector, "segmentSequence"),
                    nonnegativeInt(selector, "modelTurn"),
                    nonnegativeInt(selector, "memberSeq"),
                    nonblankText(selector, "toolCallId"));
            String controlId = nonblankText(row, "acceptanceControlId").trim();
            if (!seenMembers.add(member)) {
                throw new IllegalArgumentException("同一个父工具成员重复绑定子 Run 控制编号");
            }
            if (!seenControls.add(controlId)) {
                throw new IllegalArgumentException("同一控制编号不能绑定两个子 Run；请分别发布控制行");
            }
            bindings.add(new Binding(member, controlId));
        }
        return List.copyOf(bindings);
    }

    public static Optional<String> controlFor(JsonNode context, MemberIdentity member) {
        return parse(context).stream().filter(binding -> binding.member().equals(member))
                .map(Binding::controlId).findFirst();
    }

    /** 用已落库的等待组和成员核对创建意图，核对通过才使用绑定目标。 */
    public static MemberIdentity requireMemberIdentity(ChildRunOutboxDelivery delivery,
                                                       WaitGroup group,
                                                       WaitMember member) {
        if (delivery == null || group == null || member == null
                || !Objects.equals(group.getId(), delivery.parentWaitGroupId())
                || !Objects.equals(group.getRunId(), delivery.parentRunId())
                || !Objects.equals(group.getPlanGeneration(), delivery.planGeneration())
                || !Objects.equals(group.getNodeId(), delivery.parentNodeId())
                || !Objects.equals(group.getNodeAttempt(), delivery.nodeAttempt())
                || !Objects.equals(member.getGroupId(), delivery.parentWaitGroupId())
                || !Objects.equals(member.getRunId(), delivery.parentRunId())
                || !Objects.equals(member.getMemberIdentity(), delivery.parentMemberIdentity())
                || !Objects.equals(member.getToolCallId(), delivery.toolCallId())
                || group.getSegmentSequence() == null || group.getModelTurn() == null
                || member.getMemberSeq() == null
                || !"spawnSubAgent".equals(member.getToolName())) {
            throw new IllegalStateException("子 Run 创建意图与父等待组成员身份不一致");
        }
        return new MemberIdentity(group.getPlanGeneration(), group.getNodeId(),
                group.getNodeAttempt(), group.getSegmentSequence(), group.getModelTurn(),
                member.getMemberSeq(), member.getToolCallId());
    }

    private static void requireFields(JsonNode node, Set<String> expected, String label) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(label + "必须是 JSON 对象");
        }
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(label + "字段必须恰好是 " + expected);
        }
    }

    private static int nonnegativeInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalArgumentException(field + " 必须是非负整数");
        }
        return value.intValue();
    }

    private static String nonblankText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " 必须是非空字符串");
        }
        return value.asText();
    }
}
