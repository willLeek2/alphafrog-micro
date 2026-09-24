package world.willfrog.agent.platform.wait;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * 一个等待成员转后台的派发证明：这次后台作业的全部事实，随成员行一起持久化。
 *
 * <p>旧路径把长工具的事实写在 Run 级的一份进度记录里，一条 Run 同时只放得下一个。新调度器版本允许
 * 同一轮模型回复里发起多个 {@code executePython}，所以事实挂在成员行上：一个成员一份，互相不覆盖。</p>
 *
 * <p>结果接收方拿到它才能做两件事：按后台任务编号收到终态结果，用同一份名额凭证把容量还回去。
 * 少了任何一项，这份证明都不算完整，容量也不允许释放。</p>
 *
 * @param schemaVersion           证明格式版本；读的时候只认自己认识的那一个
 * @param operationId             外部作业身份
 * @param taskId                  后台任务编号；为空表示建任务的结果还没被证实
 * @param requestFingerprint      canonical 请求指纹，用来核对终态结果确实属于这次调用
 * @param canonicalCreateSpecJson canonical 请求规格，用来重算指纹与核对
 * @param estimateJson            预估值
 * @param reservationJson         名额预留凭证
 * @param submittedAt             提交时刻，ISO-8601 文本；写成文本是为了读写两侧都不依赖 JSON 时间模块
 */
public record WaitMemberDispatchProof(int schemaVersion,
                                      String operationId,
                                      String taskId,
                                      String requestFingerprint,
                                      String canonicalCreateSpecJson,
                                      String estimateJson,
                                      String reservationJson,
                                      String submittedAt) {

    /** 当前认识且只认识的证明格式版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WaitMemberDispatchProof {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("派发证明的格式版本不认识：" + schemaVersion);
        }
        operationId = requireText(operationId, "外部作业身份");
        requestFingerprint = requireText(requestFingerprint, "canonical 请求指纹");
        canonicalCreateSpecJson = requireText(canonicalCreateSpecJson, "canonical 请求规格");
        estimateJson = requireText(estimateJson, "预估值");
        reservationJson = requireText(reservationJson, "名额预留凭证");
        submittedAt = requireText(submittedAt, "提交时刻");
        taskId = taskId == null || taskId.isBlank() ? null : taskId.trim();
    }

    /** 后台任务编号是否已经证实。 */
    public boolean taskConfirmed() {
        return taskId != null;
    }

    public String toJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("派发证明写不成 JSON：" + describe(), e);
        }
    }

    /**
     * 读回一份派发证明。
     *
     * <p>JSON 读不出来或字段不全时返回空。调用方拿到空必须当成「这个成员没有可用证明」处理，
     * 不允许按猜测释放名额。</p>
     */
    public static Optional<WaitMemberDispatchProof> fromJson(ObjectMapper objectMapper, String json) {
        if (objectMapper == null || json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, WaitMemberDispatchProof.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 日志用的短描述，不带请求规格与名额凭证正文。 */
    public String describe() {
        return "operation=" + operationId + " task=" + (taskId == null ? "<未证实>" : taskId);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }
}
