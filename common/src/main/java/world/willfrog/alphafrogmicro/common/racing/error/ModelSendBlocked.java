package world.willfrog.alphafrogmicro.common.racing.error;

/** HTTP发送前识别的模型请求阻止原因，适配器与业务异常共同保留。 */
public interface ModelSendBlocked {

    String MODEL_INPUT_UNCONFIRMED = "MODEL_INPUT_UNCONFIRMED";
    String PROMPT_UNREADABLE = "PROMPT_UNREADABLE";
    String PROMPT_VARIABLE_INVALID = "PROMPT_VARIABLE_INVALID";
    String PROMPT_SOURCE_MISSING = "PROMPT_SOURCE_MISSING";

    /** 可持久保存并跨服务传递的稳定原因码。 */
    String code();

    /** 已排除正文、凭据与模板变量值的业务说明。 */
    String reason();

    /** 平台拒绝对应的稳定码与安全定位；本地阻止的诊断字段留空。 */
    default RacingFailure racingFailure() {
        return RacingFailure.none();
    }
}
