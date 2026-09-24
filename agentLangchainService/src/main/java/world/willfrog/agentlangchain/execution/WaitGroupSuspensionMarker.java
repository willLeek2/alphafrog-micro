package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 分段载荷里的挂起标记：这一段结束的时候把整组工具交了出去，节点本身并没有完成。
 *
 * <p>为什么要有这个标记：挂起语句会把当前分段写成「分段结果已提交」，而 Run 协调侧过去把
 * 「这一行已提交且成功」直接当成「这个节点完成了」。新版本里这两种情况必须分开——保存等待检查点
 * 只是把这一段的模型回合存了下来，节点还要等到某一次回复不再带工具请求才算完成。</p>
 *
 * <p>标记里不放等待组编号：编号要等挂起语句执行完才知道，而这条标记是挂起语句的入参。
 * 等待组由「哪一段的第几次模型回合」唯一确定，读的人用节点五字段身份加模型回合就能找到它。</p>
 *
 * @param modelTurn           产生这个等待组的模型回合
 * @param nextSegmentSequence 挂起之后继续执行的分段序号
 * @param memberCount         这一组里的工具请求个数
 */
public record WaitGroupSuspensionMarker(int modelTurn, int nextSegmentSequence, int memberCount) {

    public static final String FIELD = "waitGroup";

    public WaitGroupSuspensionMarker {
        if (modelTurn < 0) {
            throw new IllegalArgumentException("模型回合不能是负数：" + modelTurn);
        }
        if (memberCount <= 0) {
            throw new IllegalArgumentException("等待组成员数必须为正数：" + memberCount);
        }
    }

    /** 这一段是不是带着挂起标记。读的人只看标记在不在，不解释里面的数字。 */
    public static boolean present(JsonNode payload) {
        return payload != null && payload.path(FIELD).isObject();
    }

    /** 读标记；没有这个字段时返回空。 */
    public static WaitGroupSuspensionMarker read(JsonNode payload) {
        if (!present(payload)) {
            return null;
        }
        JsonNode node = payload.path(FIELD);
        return new WaitGroupSuspensionMarker(
                Math.max(0, node.path("modelTurn").asInt(0)),
                Math.max(0, node.path("nextSegmentSequence").asInt(0)),
                Math.max(0, node.path("memberCount").asInt(0)));
    }

    public ObjectNode toJson(ObjectMapper objectMapper) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("modelTurn", modelTurn);
        node.put("nextSegmentSequence", nextSegmentSequence);
        node.put("memberCount", memberCount);
        return node;
    }
}
