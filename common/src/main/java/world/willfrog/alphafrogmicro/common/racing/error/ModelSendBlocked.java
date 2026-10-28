package world.willfrog.alphafrogmicro.common.racing.error;

/** 模型请求在真正发送前被阻止；适配器与业务包装均保留这一身份。 */
public interface ModelSendBlocked {

    String MODEL_INPUT_UNCONFIRMED = "MODEL_INPUT_UNCONFIRMED";
    String PROMPT_UNREADABLE = "PROMPT_UNREADABLE";
    String PROMPT_VARIABLE_INVALID = "PROMPT_VARIABLE_INVALID";
    String PROMPT_SOURCE_MISSING = "PROMPT_SOURCE_MISSING";

    /** 可持久保存并跨服务传递的稳定原因码。 */
    String code();

    /** 安全的业务说明，不含模型正文、凭据或模板变量值。 */
    String reason();
}
