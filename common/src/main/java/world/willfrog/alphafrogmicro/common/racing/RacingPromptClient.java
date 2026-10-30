package world.willfrog.alphafrogmicro.common.racing;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.error.RacingFailure;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptBodyType;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptSetVersionSource;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVariableDeclaration;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionBody;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;
import world.willfrog.racing.idl.GetPromptSelectionRequest;
import world.willfrog.racing.idl.PromptInstructionSource;
import world.willfrog.racing.idl.PromptSelection;
import world.willfrog.racing.idl.PromptSelectionReply;
import world.willfrog.racing.idl.PromptSetMember;
import world.willfrog.racing.idl.PromptSetDefault;
import world.willfrog.racing.idl.PromptSetVersion;
import world.willfrog.racing.idl.PromptSetVersionRef;
import world.willfrog.racing.idl.PromptSetVersionReply;
import world.willfrog.racing.idl.PromptScope;
import world.willfrog.racing.idl.PromptVersion;
import world.willfrog.racing.idl.PromptVersionRead;
import world.willfrog.racing.idl.PromptVersionRef;
import world.willfrog.racing.idl.RacingService;
import world.willfrog.racing.idl.ReadPromptSetVersionRequest;
import world.willfrog.racing.idl.ReadPromptVersionsReply;
import world.willfrog.racing.idl.ReadPromptVersionsRequest;
import world.willfrog.racing.idl.ResolvePromptSelectionRequest;
import world.willfrog.racing.idl.RootIdentity;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.RuntimeIdentity;
import world.willfrog.racing.idl.TargetIdentity;
import world.willfrog.racing.idl.ValueSource;
import world.willfrog.racing.idl.ValueSourceKind;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_UNREADABLE;

/** 读取已确认的提示词版本和根选择；任何失败都停止当前发送，不重新选默认。 */
public final class RacingPromptClient {
    private final RacingTransport transport;

    public RacingPromptClient(RacingTransport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    /** 返回顺序与请求一致，每项都核对明确资源范围、编号、版本及用途。 */
    public List<PromptVersion> readVersions(ReadPromptVersionsRequest request) {
        require(request != null && request.hasContext(), "提示词读取缺少公共上下文");
        requireContext(request.getContext(), false);
        require(request.getVersionsCount() > 0, "提示词读取缺少明确版本");
        for (PromptVersionRead item : request.getVersionsList()) {
            require(item.hasVersion(), "提示词读取缺少版本定位");
            requireVersionRef(item.getVersion());
            requireText(item.getExpectedPurpose(), "提示词读取缺少预期用途");
        }
        ReadPromptVersionsReply reply = call(request.getContext(),
                (service, confirmed) -> service.readPromptVersions(
                        request.toBuilder().setContext(confirmed).build()),
                RacingPromptClient::readVersionsIdentity);
        List<PromptVersion> versions = reply.getVersions().getVersionsList();
        require(versions.size() == request.getVersionsCount(), "提示词返回数量与请求不一致");
        for (int i = 0; i < versions.size(); i++) {
            PromptVersionRead expected = request.getVersions(i);
            PromptVersion actual = versions.get(i);
            require(actual.hasReference(), "提示词返回缺少明确版本");
            requireVersionRef(actual.getReference());
            require(sameVersion(expected.getVersion(), actual.getReference()),
                    "提示词返回的资源范围或版本不一致");
            require(expected.getExpectedPurpose().equals(actual.getPurpose()), "提示词用途不一致");
        }
        return List.copyOf(versions);
    }

    /** 集合只读取固定组成；正文用途在readVersions中按成员声明再次核对。 */
    public PromptSetVersion readSet(ReadPromptSetVersionRequest request) {
        require(request != null && request.hasContext() && request.hasVersion(),
                "集合读取缺少上下文或明确版本");
        requireContext(request.getContext(), false);
        requireSetRef(request.getVersion());
        PromptSetVersionReply reply = call(request.getContext(),
                (service, confirmed) -> service.readPromptSetVersion(
                        request.toBuilder().setContext(confirmed).build()),
                RacingPromptClient::readSetIdentity);
        PromptSetVersion version = reply.getVersion();
        require(version.hasReference(), "集合返回缺少明确版本");
        requireSetRef(version.getReference());
        require(sameSetVersion(request.getVersion(), version.getReference()),
                "集合返回的资源范围或版本不一致");
        Set<String> fragments = new HashSet<>();
        for (PromptSetMember member : version.getMembersList()) {
            requireText(member.getFragmentName(), "集合片段缺少名称");
            require(fragments.add(member.getFragmentName()), "集合存在重复片段");
            require(member.hasVersion(), "集合片段缺少明确版本");
            requireVersionRef(member.getVersion());
            requireText(member.getExpectedPurpose(), "集合片段缺少预期用途");
        }
        return version;
    }

    /** 默认定位只是首次选择的输入；已有根记录返回原选择，不与当前默认重新比版。 */
    public PromptSelection resolveSelection(ResolvePromptSelectionRequest request) {
        require(request != null && request.hasContext(), "根选择缺少公共上下文");
        requireContext(request.getContext(), true);
        requireText(request.getSelectionKey(), "根选择缺少稳定选择键");
        switch (request.getOrdinaryDefaultCase()) {
            case PROMPT_DEFAULT -> {
                require(request.getPromptDefault().hasPrompt(), "根选择缺少普通提示词定位");
                require(request.getPromptDefault().getPrompt().hasScope(), "普通提示词缺少资源范围");
                requireScope(request.getPromptDefault().getPrompt().getScope());
                requireText(request.getPromptDefault().getPrompt().getPromptId(), "普通提示词缺少编号");
                requireText(request.getPromptDefault().getExpectedPurpose(), "普通提示词缺少预期用途");
            }
            case PROMPT_SET_DEFAULT -> {
                require(request.getPromptSetDefault().hasScope(), "默认集合缺少资源范围");
                requireScope(request.getPromptSetDefault().getScope());
                requireText(request.getPromptSetDefault().getBusinessName(), "默认集合缺少业务名称");
            }
            default -> throw unreadable("根选择未提供默认定位");
        }
        PromptSelectionReply reply = call(request.getContext(),
                (service, confirmed) -> service.resolvePromptSelection(
                        request.toBuilder().setContext(confirmed).build()),
                RacingPromptClient::selectionIdentity);
        PromptSelection selection = requireSelection(reply.getSelection(), request.getContext());
        require(request.getSelectionKey().equals(selection.getSelectionKey()), "返回的根选择键不一致");
        boolean expectedPrompt = request.hasPromptDefault();
        require(expectedPrompt == selection.getSelectedTarget().hasPromptVersion(), "根选择目标类型不一致");
        return selection;
    }

    /** 查询失败直接停止；缺少定位时不会转为Resolve或读取当前默认。 */
    public PromptSelection getSelection(GetPromptSelectionRequest request) {
        require(request != null && request.hasContext(), "根选择查询缺少公共上下文");
        requireContext(request.getContext(), true);
        switch (request.getLocatorCase()) {
            case SELECTION_ID -> requireText(request.getSelectionId(), "根选择查询缺少选择编号");
            case SELECTION_KEY -> requireText(request.getSelectionKey(), "根选择查询缺少选择键");
            default -> throw unreadable("根选择查询缺少定位");
        }
        PromptSelectionReply reply = call(request.getContext(),
                (service, confirmed) -> service.getPromptSelection(
                        request.toBuilder().setContext(confirmed).build()),
                RacingPromptClient::selectionIdentity);
        PromptSelection selection = requireSelection(reply.getSelection(), request.getContext());
        if (request.hasSelectionId()) {
            require(request.getSelectionId().equals(selection.getSelectionId()), "返回的根选择编号不一致");
        } else {
            require(request.getSelectionKey().equals(selection.getSelectionKey()), "返回的根选择键不一致");
        }
        return selection;
    }

    /** 保留原文和实际提供的来源，未参加实验时不编造实验或分配定位。 */
    public static PromptVersionBody toVersionBody(PromptVersion version, PromptInstructionSource source) {
        require(version != null && version.hasReference() && source != null && source.hasVersion(),
                "提示词正文或来源缺少明确版本");
        requireVersionRef(version.getReference());
        requireVersionRef(source.getVersion());
        require(sameVersion(version.getReference(), source.getVersion()), "提示词正文与来源版本不一致");
        String selectionId = optionalIdentifier(source.getSelectionId(), "选择编号无效");
        String selectionKey = optionalIdentifier(source.getSelectionKey(), "选择键无效");
        String fragmentName = optionalIdentifier(source.getFragmentName(), "集合片段名称无效");
        PromptSetVersionSource setSource = null;
        if (source.hasPromptSetVersion()) {
            PromptSetVersionRef set = source.getPromptSetVersion();
            requireSetRef(set);
            setSource = new PromptSetVersionSource(set.getScope().getEnvironment(),
                    set.getScope().getScopeInstanceId(), set.getPromptSetId(), set.getVersion());
        }
        String experimentId = null;
        String assignmentId = null;
        ValueSource valueSource = null;
        if (source.hasOrdinaryDefaultSource()) {
            require(!source.hasExperiment(), "普通默认与实验来源不能同时存在");
            valueSource = source.getOrdinaryDefaultSource();
        }
        if (source.hasExperiment()) {
            requireText(source.getExperiment().getExperimentId(), "实验来源缺少实验编号");
            require(source.getExperiment().hasValueSource(), "实验来源缺少取值来源");
            experimentId = source.getExperiment().getExperimentId();
            assignmentId = optionalIdentifier(source.getExperiment().getAssignmentId(), "实验分配编号无效");
            valueSource = source.getExperiment().getValueSource();
            requireValueSource(valueSource);
            if (valueSource.getKind() == ValueSourceKind.ENDED_FIXED) {
                require(assignmentId == null, "结束固定值不能携带虚构分配编号");
            } else {
                require(assignmentId != null, "实验来源缺少已确认分配编号");
            }
        } else if (valueSource != null) {
            requireValueSource(valueSource);
            require(valueSource.getKind() == ValueSourceKind.ORDINARY_DEFAULT,
                    "非实验提示词来源不能冒充实验取值");
        }
        PromptVersionRef reference = version.getReference();
        PromptVersionSource localSource = new PromptVersionSource(reference.getScope().getEnvironment(),
                reference.getScope().getScopeInstanceId(), reference.getPromptId(), reference.getVersion(),
                version.getPurpose(), selectionId, assignmentId, valueSourceJson(valueSource), selectionKey,
                setSource, fragmentName, experimentId);
        PromptBodyType type = switch (version.getBodyType()) {
            case PROMPT_BODY_FIXED -> PromptBodyType.FIXED;
            case PROMPT_BODY_TEMPLATE -> PromptBodyType.TEMPLATE;
            default -> throw unreadable("提示词正文类型无效");
        };
        List<PromptVariableDeclaration> variables = version.getVariablesList().stream()
                .map(variable -> new PromptVariableDeclaration(variable.getName(), variable.getRequired(),
                        variable.getAllowEmpty(), variable.hasDefaultValue() ? variable.getDefaultValue() : null))
                .toList();
        return new PromptVersionBody(localSource, type, version.getBody(), variables);
    }

    private <T> T call(RuntimeCallContext context,
                       BiFunction<RacingService, RuntimeCallContext, T> invocation,
                       Function<T, RuntimeIdentity> identityReader) {
        try {
            return transport.call(context, invocation, identityReader);
        } catch (RuntimeException failure) {
            ModelSendBlocked marker = ModelSendBlocks.find(failure).orElse(null);
            if (marker != null) {
                throw new ModelSendBlockedException(marker.code(), marker.reason(), marker.racingFailure());
            }
            if (failure instanceof RacingCallException racing) {
                throw unreadable("平台提示词调用未确认", RacingFailure.from(racing.error()));
            }
            // 远端错误文字、正文和凭据均不进入发送阻止异常。
            throw unreadable("平台提示词调用未确认");
        }
    }

    private static RuntimeIdentity readVersionsIdentity(ReadPromptVersionsReply reply) {
        require(reply != null, "平台没有返回提示词读取结果");
        if (reply.hasError()) throw unreadable("平台拒绝提示词读取", RacingFailure.from(reply.getError()));
        require(reply.hasVersions() && reply.hasActualTarget(), "提示词读取回复不完整");
        return reply.getActualTarget();
    }

    private static RuntimeIdentity readSetIdentity(PromptSetVersionReply reply) {
        require(reply != null, "平台没有返回集合读取结果");
        if (reply.hasError()) throw unreadable("平台拒绝集合读取", RacingFailure.from(reply.getError()));
        require(reply.hasVersion() && reply.hasActualTarget(), "集合读取回复不完整");
        return reply.getActualTarget();
    }

    private static RuntimeIdentity selectionIdentity(PromptSelectionReply reply) {
        require(reply != null, "平台没有返回根选择结果");
        if (reply.hasError()) throw unreadable("平台拒绝根选择请求", RacingFailure.from(reply.getError()));
        require(reply.hasSelection() && reply.hasActualTarget(), "根选择回复不完整");
        return reply.getActualTarget();
    }

    private static PromptSelection requireSelection(PromptSelection selection, RuntimeCallContext context) {
        require(selection.hasRoot() && sameRoot(context.getRoot(), selection.getRoot()), "返回的根归属不一致");
        require(selection.hasTarget() && sameLogicalTarget(context.getTarget(), selection.getTarget()),
                "返回的根选择逻辑目标不一致");
        requireText(selection.getSelectionId(), "根选择缺少记录编号");
        requireText(selection.getSelectionKey(), "根选择缺少选择键");
        require(selection.hasSelectedTarget(), "根选择缺少明确目标");
        switch (selection.getSelectedTarget().getTargetCase()) {
            case PROMPT_VERSION -> requireVersionRef(selection.getSelectedTarget().getPromptVersion());
            case PROMPT_SET_VERSION -> requireSetRef(selection.getSelectedTarget().getPromptSetVersion());
            default -> throw unreadable("根选择没有明确正文或集合版本");
        }
        requireSelectionOrigin(selection, context);
        return selection;
    }

    private static void requireSelectionOrigin(PromptSelection selection, RuntimeCallContext context) {
        require(selection.hasOrigin() && selection.getOrigin().hasSourceScope(), "根选择缺少实际来源");
        var origin = selection.getOrigin();
        requireScope(origin.getSourceScope());
        requireText(origin.getBusinessName(), "根选择缺少业务名称");
        var selectedScope = selection.getSelectedTarget().hasPromptSetVersion()
                ? selection.getSelectedTarget().getPromptSetVersion().getScope()
                : selection.getSelectedTarget().getPromptVersion().getScope();
        require(origin.getSourceScope().equals(selectedScope), "根选择来源范围与实际集合不一致");
        if (origin.hasValueSource()) {
            require(origin.getDefaultRevision() > 0, "根选择缺少真实默认修订");
            requireValueSource(origin.getValueSource());
            require(origin.getValueSource().getKind() == ValueSourceKind.ORDINARY_DEFAULT,
                    "普通根选择的来源种类不一致");
            return;
        }
        require(origin.hasExperiment() && selection.getSelectedTarget().hasPromptSetVersion(),
                "根选择缺少普通默认或集合实验来源");
        var experiment = origin.getExperiment();
        requireText(experiment.getExperimentId(), "集合实验缺少实验编号");
        require(experiment.hasValueSource(), "集合实验缺少取值来源");
        var source = experiment.getValueSource();
        requireValueSource(source);
        require(source.getConfigurationScopeInstanceId().equals(context.getTarget().getTargetScopeInstanceId()),
                "集合实验来源不属于当前目标范围");
        if (source.getKind() == ValueSourceKind.ENDED_FIXED) {
            require(!experiment.hasAssignmentId(), "结束固定集合不能伪造分配编号");
        } else {
            require(experiment.hasAssignmentId(), "集合实验缺少已保存分配");
            requireText(experiment.getAssignmentId(), "集合实验分配编号为空");
        }
        if (source.getKind() == ValueSourceKind.ORDINARY_DEFAULT) {
            try {
                var saved = PromptSetDefault.newBuilder();
                JsonFormat.parser().merge(source.getBusinessDefaultSourceJson(), saved);
                var ordinary = saved.build();
                require(ordinary.getVersion().equals(selection.getSelectedTarget().getPromptSetVersion())
                                && ordinary.getBusinessName().equals(origin.getBusinessName())
                                && ordinary.getRevision() > 0 && ordinary.getRevision() == origin.getDefaultRevision(),
                        "实验普通默认与原分配保存的集合或修订不一致");
            } catch (InvalidProtocolBufferException failure) {
                throw unreadable("实验普通默认来源无法读取");
            }
        } else {
            require(source.getKind() == ValueSourceKind.EXPERIMENT || source.getKind() == ValueSourceKind.ENDED_FIXED,
                    "集合实验来源种类不一致");
            require(origin.getDefaultRevision() == 0, "实验候选不能伪造默认修订");
        }
    }

    private static void requireContext(RuntimeCallContext context, boolean requireRoot) {
        require(context.hasTarget(), "提示词调用缺少逻辑目标");
        TargetIdentity target = context.getTarget();
        requireText(target.getEnvironment(), "逻辑目标缺少环境");
        requireText(target.getTargetScopeInstanceId(), "逻辑目标缺少创建编号");
        // 主范围可以使用空路由标签；实际范围身份由创建编号而非标签非空判断。
        requireText(target.getTargetDeploymentId(), "逻辑目标缺少部署编号");
        if (requireRoot) {
            require(context.hasRoot(), "根选择缺少根归属");
            RootIdentity root = context.getRoot();
            requireText(root.getOwnerNamespace(), "根归属缺少业务命名空间");
            requireText(root.getOwnerKind(), "根归属缺少类型");
            requireText(root.getOwnerScopeInstanceId(), "根归属缺少原范围");
            requireText(root.getRootId(), "根归属缺少根编号");
        }
    }

    private static void requireScope(PromptScope scope) {
        requireText(scope.getEnvironment(), "提示词资源缺少环境");
        requireText(scope.getScopeInstanceId(), "提示词资源缺少创建编号");
    }

    private static void requireVersionRef(PromptVersionRef reference) {
        require(reference.hasScope(), "正文版本缺少资源范围");
        requireScope(reference.getScope());
        requireText(reference.getPromptId(), "正文版本缺少编号");
        require(reference.getVersion() > 0, "正文必须指定已保存的具体版本");
    }

    private static void requireSetRef(PromptSetVersionRef reference) {
        require(reference.hasScope(), "集合版本缺少资源范围");
        requireScope(reference.getScope());
        requireText(reference.getPromptSetId(), "集合版本缺少编号");
        require(reference.getVersion() > 0, "集合必须指定已保存的具体版本");
    }

    private static boolean sameVersion(PromptVersionRef expected, PromptVersionRef actual) {
        return sameScope(expected.getScope(), actual.getScope())
                && expected.getPromptId().equals(actual.getPromptId()) && expected.getVersion() == actual.getVersion();
    }

    private static boolean sameSetVersion(PromptSetVersionRef expected, PromptSetVersionRef actual) {
        return sameScope(expected.getScope(), actual.getScope())
                && expected.getPromptSetId().equals(actual.getPromptSetId()) && expected.getVersion() == actual.getVersion();
    }

    private static boolean sameScope(PromptScope expected, PromptScope actual) {
        return expected.getEnvironment().equals(actual.getEnvironment())
                && expected.getScopeInstanceId().equals(actual.getScopeInstanceId());
    }

    private static boolean sameRoot(RootIdentity expected, RootIdentity actual) {
        return expected.getOwnerNamespace().equals(actual.getOwnerNamespace())
                && expected.getOwnerKind().equals(actual.getOwnerKind())
                && expected.getOwnerScopeInstanceId().equals(actual.getOwnerScopeInstanceId())
                && expected.getOwnerRouteTag().equals(actual.getOwnerRouteTag())
                && expected.getRootId().equals(actual.getRootId());
    }

    private static boolean sameLogicalTarget(TargetIdentity expected, TargetIdentity actual) {
        // 构建代际由transport按本次部署配置核对，不把普通发布视为新的逻辑目标。
        return expected.getEnvironment().equals(actual.getEnvironment())
                && expected.getTargetScopeInstanceId().equals(actual.getTargetScopeInstanceId())
                && expected.getTargetRouteTag().equals(actual.getTargetRouteTag())
                && expected.getTargetDeploymentId().equals(actual.getTargetDeploymentId());
    }

    private static void requireValueSource(ValueSource source) {
        require(source.getKind() != ValueSourceKind.VALUE_SOURCE_UNSPECIFIED
                && source.getKind() != ValueSourceKind.UNRECOGNIZED, "取值来源种类缺失");
        if (source.hasConfigurationVersion()) {
            require(source.getConfigurationVersion() > 0, "取值来源不能伪造配置版本");
        }
        if (source.hasCandidateId()) {
            requireText(source.getCandidateId(), "取值来源包含空候选标识");
        }
        if (source.getKind() == ValueSourceKind.EXPERIMENT || source.getKind() == ValueSourceKind.ENDED_FIXED) {
            requireText(source.getConfigurationScopeInstanceId(), "实验来源缺少配置范围");
            require(source.hasConfigurationVersion(), "实验来源缺少真实配置版本");
        }
        if (source.getKind() == ValueSourceKind.EXPERIMENT) {
            require(source.hasCandidateId(), "实验来源缺少实际候选标识");
        }
    }

    private static String valueSourceJson(ValueSource source) {
        if (source == null) {
            return null;
        }
        try {
            return JsonFormat.printer().omittingInsignificantWhitespace().print(source);
        } catch (InvalidProtocolBufferException failure) {
            throw unreadable("取值来源无法保存为JSON");
        }
    }

    private static String optionalIdentifier(String value, String reason) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        requireText(value, reason);
        return value;
    }

    private static void requireText(String value, String reason) {
        require(value != null && !value.isBlank(), reason);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw unreadable(reason);
        }
    }

    private static ModelSendBlockedException unreadable(String reason) {
        return new ModelSendBlockedException(PROMPT_UNREADABLE, reason);
    }

    private static ModelSendBlockedException unreadable(String reason, RacingFailure failure) {
        return new ModelSendBlockedException(PROMPT_UNREADABLE, reason, failure);
    }
}
