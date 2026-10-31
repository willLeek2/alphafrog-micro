package world.willfrog.alphafrogmicro.common.racing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import world.willfrog.racing.idl.EvaluateReply;
import world.willfrog.racing.idl.EvaluateRequest;
import world.willfrog.racing.idl.EvaluationValue;
import world.willfrog.racing.idl.ExperimentUsageReceipt;
import world.willfrog.racing.idl.RecordExperimentUsageReply;
import world.willfrog.racing.idl.RecordExperimentUsageRequest;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.RuntimeIdentity;
import world.willfrog.racing.idl.ValueSource;

/** 通用取值调用只接受确认的值与来源；远程失败不转换成普通默认。 */
public final class RacingExperimentClient {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RacingTransport transport;

    public RacingExperimentClient(RacingTransport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    public EvaluationValue evaluate(RuntimeCallContext context, String experimentId,
            String ordinaryDefaultJson, String ordinaryDefaultSourceJson) {
        if (context == null || !context.hasRoot() || !context.hasTarget()
                || experimentId == null || experimentId.isBlank()) {
            throw failure("INVALID_ARGUMENT", "实验取值缺少根上下文、目标或实验编号");
        }
        requireValue(ordinaryDefaultJson, "INVALID_ARGUMENT");
        requireJson(ordinaryDefaultSourceJson, "INVALID_ARGUMENT");
        EvaluateReply reply = transport.call(context,
                (service, current) -> service.evaluate(EvaluateRequest.newBuilder()
                        .setContext(current).setExperimentId(experimentId)
                        .setOrdinaryDefaultJson(ordinaryDefaultJson)
                        .setOrdinaryDefaultSourceJson(ordinaryDefaultSourceJson).build()),
                RacingExperimentClient::actualIdentity);
        EvaluationValue value = reply.getValue();
        requireValue(value.getValueJson(), "PERSISTENCE_UNCONFIRMED");
        if (!value.hasSource()) throw failure("PERSISTENCE_UNCONFIRMED", "实验取值回复缺少实际来源");
        ValueSource source = value.getSource();
        if (!context.getTarget().getTargetScopeInstanceId().equals(source.getConfigurationScopeInstanceId())
                || source.hasConfigurationVersion() && source.getConfigurationVersion() <= 0) {
            throw failure("SCOPE_MISMATCH", "实验取值的来源范围或配置版本无效");
        }
        switch (source.getKind()) {
            case EXPERIMENT -> {
                if (!source.hasConfigurationVersion() || !source.hasCandidateId()
                        || source.getCandidateId().isBlank() || value.getAssignmentId().isBlank()) {
                    throw failure("PERSISTENCE_UNCONFIRMED", "实验候选回复缺少已保存分配或明确版本");
                }
            }
            case ORDINARY_DEFAULT -> {
                if (value.getAssignmentId().isBlank() || source.hasCandidateId()) {
                    throw failure("PERSISTENCE_UNCONFIRMED", "普通默认回复缺少已保存分配或混入候选身份");
                }
                requireJson(source.getBusinessDefaultSourceJson(), "PERSISTENCE_UNCONFIRMED");
            }
            case ENDED_FIXED -> {
                if (!value.getAssignmentId().isEmpty() || !source.hasConfigurationVersion()
                        || source.hasCandidateId()) {
                    throw failure("PERSISTENCE_UNCONFIRMED", "已结束回复的固定值来源无效");
                }
            }
            default -> throw failure("PERSISTENCE_UNCONFIRMED", "实验取值回复的来源种类无效");
        }
        return value;
    }

    /** 业务错误原样抛出；成功时由传输层核对实际处理实例和代际。 */
    public ExperimentUsageReceipt recordUsage(RuntimeCallContext context, RecordExperimentUsageRequest request) {
        if (context == null || !context.hasRoot() || !context.hasTarget() || request == null
                || request.getExperimentId().isBlank() || request.getUsageAttemptId().isBlank()
                || request.getAdoptedValueJson().isBlank() || request.getOccurredAt().isBlank()) {
            throw failure("INVALID_ARGUMENT", "使用上报缺少根上下文、实验编号、尝试编号、采用值或发生时间");
        }
        RecordExperimentUsageReply reply = transport.call(context,
                (service, current) -> service.recordExperimentUsage(request.toBuilder().setContext(current).build()),
                RacingExperimentClient::usageIdentity);
        return reply.getReceipt();
    }

    private static RuntimeIdentity actualIdentity(EvaluateReply reply) {
        if (reply.hasError()) throw new RacingCallException(reply.getError());
        if (!reply.hasValue() || !reply.getValue().hasActualTarget()) {
            throw failure("PERSISTENCE_UNCONFIRMED", "实验取值回复没有确认值或实际实例身份");
        }
        return reply.getValue().getActualTarget();
    }

    private static RuntimeIdentity usageIdentity(RecordExperimentUsageReply reply) {
        if (reply.hasError()) throw new RacingCallException(reply.getError());
        if (!reply.hasReceipt() || reply.getReceipt().getUsageId().isBlank() || !reply.hasActualTarget()) {
            throw failure("PERSISTENCE_UNCONFIRMED", "使用上报回复没有确认回执或实际实例身份");
        }
        return reply.getActualTarget();
    }

    private static void requireValue(String value, String code) {
        JsonNode node = requireJson(value, code);
        if (!node.isNumber() && !node.isTextual() && !node.isObject()) {
            throw failure(code, "实验值必须是数字、字符串或JSON对象");
        }
    }

    private static JsonNode requireJson(String value, String code) {
        try {
            JsonNode node = value == null ? null : JSON.readTree(value);
            if (node == null) throw new IllegalArgumentException();
            return node;
        } catch (Exception ex) {
            throw failure(code, "实验值或来源不是完整的JSON");
        }
    }

    private static RacingCallException failure(String code, String message) {
        return RacingCallException.failure(code, message);
    }
}
