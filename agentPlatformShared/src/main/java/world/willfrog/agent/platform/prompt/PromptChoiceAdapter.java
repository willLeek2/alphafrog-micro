package world.willfrog.agent.platform.prompt;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import world.willfrog.alphafrogmicro.common.racing.RacingCallException;
import world.willfrog.alphafrogmicro.common.racing.RacingExperimentClient;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptClient;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.error.RacingFailure;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptSetVersionSource;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionBody;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;
import world.willfrog.racing.idl.EvaluationValue;
import world.willfrog.racing.idl.PromptExperimentSource;
import world.willfrog.racing.idl.PromptInstructionSource;
import world.willfrog.racing.idl.PromptScope;
import world.willfrog.racing.idl.PromptSetVersionRef;
import world.willfrog.racing.idl.PromptTargetRef;
import world.willfrog.racing.idl.PromptVersionRead;
import world.willfrog.racing.idl.PromptVersionRef;
import world.willfrog.racing.idl.ReadPromptVersionsRequest;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.ValueSource;
import world.willfrog.racing.idl.ValueSourceKind;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_UNREADABLE;

/** 将业务的提示词实验值解释为明确正文版本；不选择新默认，也不接入运行入口。 */
public final class PromptChoiceAdapter {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final RacingExperimentClient experiments;
    private final RacingPromptClient prompts;

    public PromptChoiceAdapter(RacingExperimentClient experiments, RacingPromptClient prompts) {
        this.experiments = Objects.requireNonNull(experiments, "experiments");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
    }

    /** 未配置实验时返回原对象；实验的普通默认仍是原根集合片段，不读取当前默认。 */
    public PromptVersionBody resolve(RuntimeCallContext context, String experimentId,
                                     PromptVersionBody ordinaryDefault, String expectedPurpose) {
        try {
            requireContext(context);
            check(ordinaryDefault != null && expectedPurpose != null && !expectedPurpose.isBlank(),
                    "提示词选择缺少原片段或业务用途");
            checkUtf8(expectedPurpose);
            ordinaryDefault.requirePurpose(expectedPurpose);
            if (experimentId == null || experimentId.isEmpty()) return ordinaryDefault;
            check(!experimentId.isBlank(), "提示词实验编号不能只有空白");
            checkUtf8(experimentId);

            PromptVersionRef ordinaryVersion = versionOf(ordinaryDefault.source());
            PromptTargetRef ordinaryTarget = PromptTargetRef.newBuilder().setPromptVersion(ordinaryVersion).build();
            PromptInstructionSource ordinarySource = ordinarySource(ordinaryDefault.source(), ordinaryVersion);
            EvaluationValue value = experiments.evaluate(context, experimentId, print(ordinaryTarget), print(ordinarySource));
            PromptVersionRef selected = parseTarget(value.getValueJson());
            ValueSource actualSource = value.getSource();
            // 来源中的所有字符串也须能严格按UTF-8保存，不把非法代理字符替换后再继续。
            strictObject(print(actualSource));
            if (!actualSource.getBusinessDefaultSourceJson().isEmpty()) {
                strictObject(actualSource.getBusinessDefaultSourceJson());
            }
            PromptInstructionSource.Builder source;
            if (actualSource.getKind() == ValueSourceKind.ORDINARY_DEFAULT) {
                check(ordinaryVersion.equals(selected), "实验普通默认返回的正文不是原根片段");
                check(ordinarySource.equals(parseOrdinarySource(actualSource.getBusinessDefaultSourceJson())),
                        "实验普通默认返回的出处与原根片段不一致");
                source = ordinarySource.toBuilder().clearOrdinaryDefaultSource();
            } else {
                // 实验候选或结束固定值不冒充原集合成员；具体取值来源随实验保存。
                check(actualSource.getKind() == ValueSourceKind.EXPERIMENT
                                || actualSource.getKind() == ValueSourceKind.ENDED_FIXED,
                        "提示词实验缺少有效取值来源");
                source = PromptInstructionSource.newBuilder().setVersion(selected);
            }
            PromptExperimentSource.Builder experiment = PromptExperimentSource.newBuilder()
                    .setExperimentId(experimentId).setValueSource(actualSource);
            if (actualSource.getKind() != ValueSourceKind.ENDED_FIXED) {
                check(!value.getAssignmentId().isBlank(), "提示词实验缺少已确认分配编号");
                checkUtf8(value.getAssignmentId());
                experiment.setAssignmentId(value.getAssignmentId());
            } else {
                check(value.getAssignmentId().isEmpty(), "结束固定值不能携带分配编号");
            }
            source.setExperiment(experiment);
            var versions = prompts.readVersions(ReadPromptVersionsRequest.newBuilder().setContext(context)
                    .addVersions(PromptVersionRead.newBuilder().setVersion(selected).setExpectedPurpose(expectedPurpose))
                    .build());
            return RacingPromptClient.toVersionBody(versions.get(0), source.build()).requirePurpose(expectedPurpose);
        } catch (RuntimeException failure) {
            ModelSendBlocked marker = ModelSendBlocks.find(failure).orElse(null);
            if (marker != null) {
                throw new ModelSendBlockedException(marker.code(), marker.reason(), marker.racingFailure());
            }
            if (failure instanceof RacingCallException racing) {
                throw new ModelSendBlockedException(PROMPT_UNREADABLE,
                        "提示词实验选择或明确版本读取未确认", RacingFailure.from(racing.error()));
            }
            throw blocked("提示词实验选择或明确版本读取未确认");
        }
    }

    private static PromptInstructionSource ordinarySource(PromptVersionSource source, PromptVersionRef version) {
        check(absent(source.experimentId()) && absent(source.experimentValueId()), "普通默认片段不能混入其他实验分配");
        strictObject(source.valueSourceJson());
        ValueSource.Builder value = ValueSource.newBuilder();
        try {
            JsonFormat.parser().merge(source.valueSourceJson(), value);
        } catch (Exception invalid) {
            throw blocked("原片段的普通默认来源无法解释");
        }
        check(value.getKind() == ValueSourceKind.ORDINARY_DEFAULT, "原片段不是普通默认来源");
        check(!value.hasConfigurationVersion() || value.getConfigurationVersion() > 0, "原片段包含无效配置版本");
        check(!value.hasCandidateId() || !value.getCandidateId().isBlank(), "原片段包含空候选编号");
        PromptInstructionSource.Builder result = PromptInstructionSource.newBuilder().setVersion(version)
                .setOrdinaryDefaultSource(value);
        if (!absent(source.selectionId())) result.setSelectionId(identifier(source.selectionId()));
        if (!absent(source.selectionKey())) result.setSelectionKey(identifier(source.selectionKey()));
        if (!absent(source.fragmentName())) result.setFragmentName(identifier(source.fragmentName()));
        PromptSetVersionSource set = source.promptSetVersion();
        if (set != null) {
            result.setPromptSetVersion(PromptSetVersionRef.newBuilder()
                    .setScope(scope(set.environment(), set.scopeInstanceId()))
                    .setPromptSetId(identifier(set.promptSetId())).setVersion(set.version()));
        }
        return result.build();
    }

    private static PromptVersionRef versionOf(PromptVersionSource source) {
        return PromptVersionRef.newBuilder().setScope(scope(source.environment(), source.scopeInstanceId()))
                .setPromptId(identifier(source.promptId())).setVersion(source.version()).build();
    }

    private static PromptInstructionSource parseOrdinarySource(String json) {
        strictObject(json);
        try {
            PromptInstructionSource.Builder source = PromptInstructionSource.newBuilder();
            JsonFormat.parser().merge(json, source);
            return source.build();
        } catch (Exception invalid) {
            throw blocked("实验普通默认出处不是有效的指令来源");
        }
    }

    private static PromptVersionRef parseTarget(String json) {
        strictObject(json);
        try {
            PromptTargetRef.Builder target = PromptTargetRef.newBuilder();
            JsonFormat.parser().merge(json, target);
            check(target.hasPromptVersion(), "提示词实验值必须明确指向正文版本");
            PromptVersionRef version = target.getPromptVersion();
            check(version.hasScope(), "提示词实验值缺少正文资源范围");
            identifier(version.getScope().getEnvironment());
            identifier(version.getScope().getScopeInstanceId());
            identifier(version.getPromptId());
            check(version.getVersion() > 0, "提示词实验必须指定正版本号");
            return version;
        } catch (ModelSendBlockedException blocked) {
            throw blocked;
        } catch (Exception invalid) {
            throw blocked("提示词实验值不是有效的明确版本定位");
        }
    }

    private static JsonNode strictObject(String json) {
        try {
            checkUtf8(json);
            JsonNode value = JSON.readTree(json);
            check(value != null && value.isObject(), "提示词取值或来源必须为完整JSON对象");
            checkTreeUtf8(value);
            return value;
        } catch (ModelSendBlockedException blocked) {
            throw blocked;
        } catch (Exception invalid) {
            throw blocked("提示词取值或来源不是严格JSON对象");
        }
    }

    private static void checkTreeUtf8(JsonNode value) {
        if (value.isTextual()) checkUtf8(value.textValue());
        else if (value.isObject()) value.fields().forEachRemaining(field -> {
            checkUtf8(field.getKey());
            checkTreeUtf8(field.getValue());
        });
        else if (value.isArray()) value.forEach(PromptChoiceAdapter::checkTreeUtf8);
    }

    private static void checkUtf8(String text) {
        check(text != null, "提示词取值或来源缺少内容");
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        } catch (Exception invalid) {
            throw blocked("提示词取值或来源包含无法按UTF-8保存的字符");
        }
    }

    private static String print(MessageOrBuilder value) {
        try {
            return JsonFormat.printer().omittingInsignificantWhitespace().print(value);
        } catch (Exception invalid) {
            throw blocked("提示词版本或来源无法序列化");
        }
    }

    private static PromptScope scope(String environment, String instanceId) {
        return PromptScope.newBuilder().setEnvironment(identifier(environment)).setScopeInstanceId(identifier(instanceId)).build();
    }

    private static String identifier(String value) {
        check(value != null && !value.isBlank(), "提示词来源的标识不完整");
        checkUtf8(value);
        return value;
    }

    private static void requireContext(RuntimeCallContext context) {
        check(context != null && context.hasRoot() && context.hasTarget(), "提示词选择缺少已确认运行上下文");
        identifier(context.getRoot().getOwnerNamespace());
        identifier(context.getRoot().getOwnerKind());
        identifier(context.getRoot().getOwnerScopeInstanceId());
        identifier(context.getRoot().getRootId());
        identifier(context.getTarget().getEnvironment());
        identifier(context.getTarget().getTargetScopeInstanceId());
        identifier(context.getTarget().getTargetDeploymentId());
    }

    private static boolean absent(String value) {
        return value == null || value.isEmpty();
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw blocked(reason);
    }

    private static ModelSendBlockedException blocked(String reason) {
        return new ModelSendBlockedException(PROMPT_UNREADABLE, reason);
    }
}
