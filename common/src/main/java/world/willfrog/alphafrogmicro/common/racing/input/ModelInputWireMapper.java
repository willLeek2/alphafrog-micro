package world.willfrog.alphafrogmicro.common.racing.input;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptSetVersionSource;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;
import world.willfrog.racing.idl.ModelInputContentKind;
import world.willfrog.racing.idl.ModelInputMessageLocation;
import world.willfrog.racing.idl.ModelInputSourceBinding;
import world.willfrog.racing.idl.ModelInputStructureLocation;
import world.willfrog.racing.idl.ModelInputToolLocation;
import world.willfrog.racing.idl.ModelInputToolResultLocation;
import world.willfrog.racing.idl.PromptExperimentSource;
import world.willfrog.racing.idl.PromptInstructionSource;
import world.willfrog.racing.idl.PromptScope;
import world.willfrog.racing.idl.PromptSetVersionRef;
import world.willfrog.racing.idl.PromptVersionRef;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.StoreModelInputRequest;
import world.willfrog.racing.idl.ValueSource;
import world.willfrog.racing.idl.ValueSourceKind;

/** 把最终输入的位置和不可变来源快照转换为平台保存协议，不重新选择正文。 */
public final class ModelInputWireMapper {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public StoreModelInputRequest toRequest(RuntimeCallContext context, ModelInputAttempt attempt) {
        if (context == null || attempt == null) {
            throw blocked("输入保存缺少运行上下文或完整输入");
        }
        attempt.sources().validate(attempt.bodyJson());
        JsonNode body = readObject(attempt.bodyJson());
        StoreModelInputRequest.Builder request = StoreModelInputRequest.newBuilder()
                .setContext(context)
                .setLogicalCallId(attempt.logicalCallId().toString())
                .setAttemptUuid(attempt.attemptId().toString())
                .setInputJson(attempt.bodyJson())
                .setEndpointName(attempt.endpointName())
                .setUtf8Length(attempt.utf8Length())
                .setSha256(attempt.sha256());
        for (ModelInputSource source : attempt.sources().parts()) {
            request.addSources(sourceBinding(body, source));
        }
        return request.build();
    }

    private static ModelInputSourceBinding sourceBinding(JsonNode body, ModelInputSource source) {
        String pointer = source.jsonPointer();
        ModelInputSourceBinding.Builder binding = ModelInputSourceBinding.newBuilder();
        String messagePrefix = body.has("messages") ? "/messages/" : "/input/messages/";
        if (pointer.startsWith(messagePrefix)) {
            int index = arrayIndex(pointer, messagePrefix);
            JsonNode message = body.at(messagePrefix.substring(0, messagePrefix.length() - 1)).get(index);
            if ("tool".equals(message.path("role").asText())) {
                binding.setToolResult(ModelInputToolResultLocation.newBuilder()
                        .setMessageIndex(index).setFieldPath(pointer)
                        .setToolCallId(requiredText(message.get("tool_call_id"), "工具结果缺少实际调用编号")));
            } else {
                binding.setMessage(ModelInputMessageLocation.newBuilder()
                        .setMessageIndex(index).setFieldPath(pointer));
            }
        } else if (pointer.startsWith("/tools/")) {
            int index = arrayIndex(pointer, "/tools/");
            JsonNode tool = body.path("tools").get(index);
            JsonNode name = tool.has("function") ? tool.path("function").get("name") : tool.get("name");
            binding.setToolDefinition(ModelInputToolLocation.newBuilder().setFieldPath(pointer)
                    .setToolName(requiredText(name, "工具定义缺少实际工具名称")));
        } else if (pointer.equals("/response_format") || pointer.startsWith("/response_format/")) {
            binding.setOutputStructure(ModelInputStructureLocation.newBuilder().setFieldPath(pointer));
        } else {
            throw blocked("输入来源不能转换为平台位置");
        }
        binding.setContentKind(source.origin() == ModelInputSource.Origin.APPLICATION_INSTRUCTION
                ? ModelInputContentKind.MODEL_INPUT_APPLICATION_INSTRUCTION
                : ModelInputContentKind.MODEL_INPUT_RUNTIME_DATA);
        for (PromptVersionSource version : source.versions()) {
            binding.addInstructionSources(instructionSource(version));
        }
        return binding.build();
    }

    private static PromptInstructionSource instructionSource(PromptVersionSource source) {
        for (String optional : new String[] { source.selectionId(), source.selectionKey(), source.fragmentName(),
                source.experimentId(), source.experimentValueId(), source.valueSourceJson() }) {
            if (optional != null && !optional.isEmpty() && optional.isBlank()) {
                throw blocked("指令来源的可选字段不能只有空白");
            }
        }
        PromptInstructionSource.Builder instruction = PromptInstructionSource.newBuilder()
                .setVersion(PromptVersionRef.newBuilder()
                        .setScope(scope(source.environment(), source.scopeInstanceId()))
                        .setPromptId(source.promptId()).setVersion(source.version()));
        // 用途由这个不可变正文版本确定，协议只传版本定位。
        if (hasText(source.selectionId())) instruction.setSelectionId(source.selectionId());
        if (hasText(source.selectionKey())) instruction.setSelectionKey(source.selectionKey());
        if (hasText(source.fragmentName())) instruction.setFragmentName(source.fragmentName());
        PromptSetVersionSource set = source.promptSetVersion();
        if (set != null) {
            instruction.setPromptSetVersion(PromptSetVersionRef.newBuilder()
                    .setScope(scope(set.environment(), set.scopeInstanceId()))
                    .setPromptSetId(set.promptSetId()).setVersion(set.version()));
        }
        boolean experiment = hasText(source.experimentId());
        boolean hasValueSource = hasText(source.valueSourceJson());
        if (experiment) {
            if (!hasValueSource) throw blocked("实验指令缺少实际取值来源");
            ValueSource actualSource = valueSource(source.valueSourceJson());
            if (actualSource.getConfigurationScopeInstanceId().isBlank()) {
                throw blocked("实验指令缺少实际配置范围");
            }
            boolean hasAssignment = hasText(source.experimentValueId());
            if (actualSource.getKind() == ValueSourceKind.ENDED_FIXED) {
                if (hasAssignment) throw blocked("结束固定值不能携带虚构分配编号");
            } else if (!hasAssignment) {
                throw blocked("实验取值来源缺少已确认分配编号");
            }
            PromptExperimentSource.Builder experimentSource = PromptExperimentSource.newBuilder()
                    .setExperimentId(source.experimentId()).setValueSource(actualSource);
            if (hasAssignment) {
                experimentSource.setAssignmentId(source.experimentValueId());
            }
            instruction.setExperiment(experimentSource);
        } else {
            if (hasText(source.experimentValueId())) throw blocked("分配来源缺少对应实验编号");
            if (hasValueSource) {
                ValueSource ordinary = valueSource(source.valueSourceJson());
                if (ordinary.getKind() != ValueSourceKind.ORDINARY_DEFAULT) {
                    throw blocked("非实验指令的取值来源必须为普通默认");
                }
                instruction.setOrdinaryDefaultSource(ordinary);
            }
        }
        return instruction.build();
    }

    private static ValueSource valueSource(String json) {
        // 不忽略未知字段，也不把无法解释的来源降级为空来源。
        readObject(json);
        ValueSource.Builder source = ValueSource.newBuilder();
        try {
            JsonFormat.parser().merge(json, source);
        } catch (InvalidProtocolBufferException failure) {
            throw blocked("指令取值来源不能转换为平台协议");
        }
        if (source.getKind() == ValueSourceKind.VALUE_SOURCE_UNSPECIFIED
                || source.getKind() == ValueSourceKind.UNRECOGNIZED) {
            throw blocked("指令取值来源缺少有效类型");
        }
        if (source.hasConfigurationVersion() && source.getConfigurationVersion() <= 0) {
            throw blocked("指令取值来源不能使用无效配置版本");
        }
        if (source.hasCandidateId() && source.getCandidateId().isBlank()) {
            throw blocked("指令取值来源不能使用空候选编号");
        }
        if (source.getKind() != ValueSourceKind.EXPERIMENT && source.hasCandidateId()) {
            throw blocked("普通默认或结束固定值不能携带候选编号");
        }
        if (source.getKind() == ValueSourceKind.EXPERIMENT || source.getKind() == ValueSourceKind.ENDED_FIXED) {
            if (source.getConfigurationScopeInstanceId().isBlank() || !source.hasConfigurationVersion()) {
                throw blocked("实验取值来源缺少真实配置范围或版本");
            }
        }
        if (source.getKind() == ValueSourceKind.EXPERIMENT && !source.hasCandidateId()) {
            throw blocked("实验取值来源缺少实际候选编号");
        }
        return source.build();
    }

    private static PromptScope scope(String environment, String instanceId) {
        return PromptScope.newBuilder().setEnvironment(environment).setScopeInstanceId(instanceId).build();
    }

    private static int arrayIndex(String pointer, String prefix) {
        int separator = pointer.indexOf('/', prefix.length());
        String index = pointer.substring(prefix.length(), separator < 0 ? pointer.length() : separator);
        try {
            return Integer.parseInt(index);
        } catch (NumberFormatException failure) {
            throw blocked("输入来源缺少有效数组位置");
        }
    }

    private static String requiredText(JsonNode value, String reason) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw blocked(reason);
        return value.textValue();
    }

    private static JsonNode readObject(String json) {
        try {
            JsonNode value = JSON.readTree(json);
            if (value != null && value.isObject()) return value;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw blocked("输入或来源不是有效JSON对象");
        }
        throw blocked("输入或来源不是有效JSON对象");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ModelSendBlockedException blocked(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }
}
