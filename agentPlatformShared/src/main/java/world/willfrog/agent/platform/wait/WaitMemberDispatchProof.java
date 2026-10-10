package world.willfrog.agent.platform.wait;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param schemaVersion           证明格式版本；旧版与含完整请求的新版均可读
 * @param operationId             外部作业身份
 * @param taskId                  后台任务编号；为空表示建任务的结果还没被证实
 * @param requestFingerprint      canonical 请求指纹，用来核对终态结果确实属于这次调用
 * @param canonicalCreateSpecJson canonical 请求规格，用来重算指纹与核对
 * @param estimateJson            预估值
 * @param reservationJson         名额预留凭证
 * @param submittedAt             提交时刻，ISO-8601 文本；写成文本是为了读写两侧都不依赖 JSON 时间模块
 * @param createRequestJson       发往沙箱的完整请求；版本 2 起在调用前持久保存，供崩溃后原样重放
 * @param createRequestExpired    已结清成员所属工作区删盘后才写入；表明完整请求按保留期清除
 */
public record WaitMemberDispatchProof(int schemaVersion,
                                      String operationId,
                                      String taskId,
                                      String requestFingerprint,
                                      String canonicalCreateSpecJson,
                                      String estimateJson,
                                      String reservationJson,
                                      String submittedAt,
                                      String createRequestJson,
                                      String workspaceRefusalCode,
                                      @JsonInclude(JsonInclude.Include.NON_NULL)
                                      Boolean createRequestExpired) {

    /** 旧版证明仍须可读；它没有完整请求，只能沿旧取消路径安全收尾。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;
    /** 新版证明在首次发出请求前保存完整的创建请求。 */
    public static final int REPLAYABLE_SCHEMA_VERSION = 2;

    public WaitMemberDispatchProof(int schemaVersion, String operationId, String taskId,
                                   String requestFingerprint, String canonicalCreateSpecJson,
                                   String estimateJson, String reservationJson, String submittedAt) {
        this(schemaVersion, operationId, taskId, requestFingerprint, canonicalCreateSpecJson,
                estimateJson, reservationJson, submittedAt, null, null, null);
    }

    public WaitMemberDispatchProof(int schemaVersion, String operationId, String taskId,
                                   String requestFingerprint, String canonicalCreateSpecJson,
                                   String estimateJson, String reservationJson, String submittedAt,
                                   String createRequestJson) {
        this(schemaVersion, operationId, taskId, requestFingerprint, canonicalCreateSpecJson,
                estimateJson, reservationJson, submittedAt, createRequestJson, null, null);
    }

    public WaitMemberDispatchProof(int schemaVersion, String operationId, String taskId,
                                   String requestFingerprint, String canonicalCreateSpecJson,
                                   String estimateJson, String reservationJson, String submittedAt,
                                   String createRequestJson, String workspaceRefusalCode) {
        this(schemaVersion, operationId, taskId, requestFingerprint, canonicalCreateSpecJson,
                estimateJson, reservationJson, submittedAt, createRequestJson, workspaceRefusalCode, null);
    }

    public WaitMemberDispatchProof {
        if (schemaVersion != CURRENT_SCHEMA_VERSION && schemaVersion != REPLAYABLE_SCHEMA_VERSION) {
            throw new IllegalArgumentException("派发证明的格式版本不认识：" + schemaVersion);
        }
        operationId = requireText(operationId, "外部作业身份");
        requestFingerprint = requireText(requestFingerprint, "canonical 请求指纹");
        canonicalCreateSpecJson = requireText(canonicalCreateSpecJson, "canonical 请求规格");
        estimateJson = requireText(estimateJson, "预估值");
        reservationJson = requireText(reservationJson, "名额预留凭证");
        submittedAt = requireText(submittedAt, "提交时刻");
        taskId = taskId == null || taskId.isBlank() ? null : taskId.trim();
        if (Boolean.TRUE.equals(createRequestExpired)) {
            if (schemaVersion != REPLAYABLE_SCHEMA_VERSION || createRequestJson != null) {
                throw new IllegalArgumentException("已清理请求证明的格式无效");
            }
        } else if (schemaVersion == REPLAYABLE_SCHEMA_VERSION) {
            createRequestJson = requireText(createRequestJson, "可重放的完整创建请求");
        }
        if (workspaceRefusalCode != null) {
            if (schemaVersion != REPLAYABLE_SCHEMA_VERSION || taskId != null
                    || !isWorkspaceRefusalCode(workspaceRefusalCode)) {
                throw new IllegalArgumentException("工作区拒绝证明无效");
            }
        }
    }

    /** 后台任务编号是否已经证实。 */
    public boolean taskConfirmed() {
        return taskId != null;
    }

    public boolean replayable() {
        return schemaVersion == REPLAYABLE_SCHEMA_VERSION && !Boolean.TRUE.equals(createRequestExpired);
    }

    public boolean workspaceRefused() {
        return workspaceRefusalCode != null;
    }

    public WaitMemberDispatchProof withWorkspaceRefusal(String code) {
        if (!replayable() || taskConfirmed() || !isWorkspaceRefusalCode(code)) {
            throw new IllegalArgumentException("只有完整且未绑定任务的请求能记录工作区拒绝");
        }
        return new WaitMemberDispatchProof(schemaVersion, operationId, taskId,
                requestFingerprint, canonicalCreateSpecJson, estimateJson, reservationJson,
                submittedAt, createRequestJson, code, null);
    }

    public static boolean isWorkspaceRefusalCode(String code) {
        return "WORKSPACE_DIRTY".equals(code)
                || "WORKSPACE_NOT_FOUND".equals(code)
                || "WORKSPACE_OWNERSHIP_MISMATCH".equals(code)
                || "WORKSPACE_DELETED".equals(code)
                || "WORKSPACE_DELETING".equals(code)
                || "WORKSPACE_IDENTITY_CONFLICT".equals(code)
                || "WORKSPACE_UNSUPPORTED".equals(code)
                || "WORKSPACE_RUN_INELIGIBLE".equals(code);
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
