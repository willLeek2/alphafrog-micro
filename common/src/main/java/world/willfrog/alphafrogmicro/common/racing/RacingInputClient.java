package world.willfrog.alphafrogmicro.common.racing;

import java.util.Objects;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputAttempt;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputStore;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputWireMapper;
import world.willfrog.racing.idl.ModelInputReceipt;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.RuntimeIdentity;
import world.willfrog.racing.idl.StoreModelInputReply;

/** 只有完整输入已经提交且回执与本次输入一致，才允许调用方继续模型发送。 */
public final class RacingInputClient {
    private final RacingTransport transport;
    private final ModelInputWireMapper mapper = new ModelInputWireMapper();

    public RacingInputClient(RacingTransport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    /** 绑定公共准备已经确认的根归属与目标；保存过程不重新选择默认正文。 */
    public ModelInputStore boundTo(RuntimeCallContext confirmedContext) {
        if (confirmedContext == null || !confirmedContext.hasRoot() || !confirmedContext.hasTarget()
                || confirmedContext.getRoot().getRootId().isBlank()
                || confirmedContext.getTarget().getTargetScopeInstanceId().isBlank()) {
            throw blocked(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED);
        }
        return attempt -> storeConfirmed(confirmedContext, attempt);
    }

    private ModelInputStore.Confirmation storeConfirmed(RuntimeCallContext context, ModelInputAttempt attempt) {
        try {
            if (attempt == null) throw blocked(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED);
            // 不自动重试保存失败；同一不可变输入只发起这一次保存调用。
            StoreModelInputReply reply = transport.call(context,
                    (service, currentContext) -> service.storeModelInput(mapper.toRequest(currentContext, attempt)),
                    RacingInputClient::actualTarget);
            ModelInputReceipt receipt = reply.getStored();
            if (receipt.getModelInputId().isBlank()
                    || !receipt.getLogicalCallId().equals(attempt.logicalCallId().toString())
                    || !receipt.getAttemptUuid().equals(attempt.attemptId().toString())
                    || !receipt.getSha256().equals(attempt.sha256())
                    || receipt.getUtf8Length() != attempt.utf8Length()) {
                throw blocked(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED);
            }
            return new ModelInputStore.Confirmation(receipt.getModelInputId(), attempt.attemptId(),
                    receipt.getSha256(), receipt.getUtf8Length());
        } catch (RuntimeException failure) {
            ModelSendBlocked marker = ModelSendBlocks.find(failure).orElse(null);
            if (marker != null) throw new ModelSendBlockedException(marker.code(), marker.reason());
            if (failure instanceof RacingCallException racing) throw blocked(racing.error().getCode());
            // 不保留远程消息、HTTP异常或cause，避免正文与变量值进入异常日志。
            throw blocked(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED);
        }
    }

    private static RuntimeIdentity actualTarget(StoreModelInputReply reply) {
        if (reply == null) throw blocked(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED);
        // 业务阻止原因优先于身份核对，错误回复不伪装成保存成功。
        if (reply.hasError()) throw blocked(reply.getError().getCode());
        if (!reply.hasStored() || !reply.hasActualTarget()) {
            throw blocked(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED);
        }
        return reply.getActualTarget();
    }

    private static ModelSendBlockedException blocked(String code) {
        String stableCode = ModelSendBlocks.isBlockedCode(code) ? code : ModelSendBlocked.MODEL_INPUT_UNCONFIRMED;
        String reason = switch (stableCode) {
            case ModelSendBlocked.PROMPT_UNREADABLE -> "本次输入使用的正文版本不可读取";
            case ModelSendBlocked.PROMPT_VARIABLE_INVALID -> "本次输入的正文变量不符合要求";
            case ModelSendBlocked.PROMPT_SOURCE_MISSING -> "本次输入的指令来源缺失或无效";
            default -> "完整模型输入保存尚未确认";
        };
        return new ModelSendBlockedException(stableCode, reason);
    }
}
