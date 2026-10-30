package world.willfrog.alphafrogmicro.common.racing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptTemplateRenderer;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionBody;
import world.willfrog.racing.idl.GetPromptSelectionRequest;
import world.willfrog.racing.idl.PromptInstructionSource;
import world.willfrog.racing.idl.PromptSelection;
import world.willfrog.racing.idl.PromptSetMember;
import world.willfrog.racing.idl.PromptSetVersion;
import world.willfrog.racing.idl.PromptVersion;
import world.willfrog.racing.idl.PromptVersionRead;
import world.willfrog.racing.idl.ReadPromptSetVersionRequest;
import world.willfrog.racing.idl.ReadPromptVersionsRequest;
import world.willfrog.racing.idl.RuntimeCallContext;

/** 按根运行已经确认的集合读取所需片段，不随当前默认切换，也不扩大实际来源清单。 */
public final class RacingPromptSetReader {
    private final RacingPromptClient client;

    public RacingPromptSetReader(RacingPromptClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** 业务明确提供片段及预期用途，不能以集合自身的用途声明代替业务核对。 */
    public Map<String, PromptVersionBody> readFragments(RuntimeCallContext context, PromptSelection binding,
                                                       Map<String, String> expectedPurposes) {
        require(context != null && binding != null && !binding.getSelectionId().isBlank(),
                "读取集合片段缺少已确认根选择");
        require(expectedPurposes != null && !expectedPurposes.isEmpty(), "读取集合片段缺少业务用途清单");
        Map<String, String> expected = new LinkedHashMap<>(expectedPurposes);
        expected.forEach((fragment, purpose) -> require(fragment != null && !fragment.isBlank()
                && purpose != null && !purpose.isBlank(), "业务片段名称及预期用途不能为空"));

        // 跨服务传入的绑定先与平台持久记录核对，不能只信任请求自报的集合版本。
        PromptSelection saved = client.getSelection(GetPromptSelectionRequest.newBuilder()
                .setContext(context).setSelectionId(binding.getSelectionId()).build());
        require(stableSelection(saved).equals(stableSelection(binding)),
                "传入的集合绑定与平台保存的选择不一致");
        require(saved.getSelectedTarget().hasPromptSetVersion(), "根选择不是具体集合版本");
        PromptSetVersion set = client.readSet(ReadPromptSetVersionRequest.newBuilder().setContext(context)
                .setVersion(saved.getSelectedTarget().getPromptSetVersion()).build());
        Map<String, PromptSetMember> members = new LinkedHashMap<>();
        set.getMembersList().forEach(member -> members.put(member.getFragmentName(), member));
        List<PromptSetMember> requiredMembers = new ArrayList<>();
        ReadPromptVersionsRequest.Builder request = ReadPromptVersionsRequest.newBuilder().setContext(context);
        expected.forEach((fragment, purpose) -> {
            PromptSetMember member = members.get(fragment);
            require(member != null, "所选集合缺少业务片段：" + fragment);
            require(purpose.equals(member.getExpectedPurpose()), "所选集合片段用途不符：" + fragment);
            requiredMembers.add(member);
            request.addVersions(PromptVersionRead.newBuilder().setVersion(member.getVersion()).setExpectedPurpose(purpose));
        });
        List<PromptVersion> versions = client.readVersions(request.build());
        Map<String, PromptVersionBody> result = new LinkedHashMap<>();
        for (int i = 0; i < requiredMembers.size(); i++) {
            PromptSetMember member = requiredMembers.get(i);
            PromptInstructionSource source = PromptInstructionSource.newBuilder().setVersion(member.getVersion())
                    .setSelectionId(saved.getSelectionId()).setSelectionKey(saved.getSelectionKey())
                    .setPromptSetVersion(set.getReference()).setFragmentName(member.getFragmentName()).build();
            if (saved.getOrigin().hasExperiment()) {
                source = source.toBuilder().setExperiment(saved.getOrigin().getExperiment()).build();
            } else {
                source = source.toBuilder().setOrdinaryDefaultSource(saved.getOrigin().getValueSource()).build();
            }
            result.put(member.getFragmentName(), RacingPromptClient.toVersionBody(versions.get(i), source));
        }
        return Collections.unmodifiableMap(result);
    }

    /** 返回的来源只覆盖所请求片段；渲染失败不改读其他片段或当前默认。 */
    public Map<String, PromptText> renderFragments(RuntimeCallContext context, PromptSelection binding,
                                                  Map<String, String> expectedPurposes,
                                                  Map<String, Map<String, String>> values) {
        Map<String, PromptVersionBody> bodies = readFragments(context, binding, expectedPurposes);
        Map<String, PromptText> rendered = new LinkedHashMap<>();
        bodies.forEach((fragment, body) -> rendered.put(fragment, PromptTemplateRenderer.render(body,
                values == null ? Map.of() : values.getOrDefault(fragment, Map.of()))));
        return Collections.unmodifiableMap(rendered);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_UNREADABLE, reason);
        }
    }

    private static PromptSelection stableSelection(PromptSelection selection) {
        // 普通发布可以更新本次代际，重复确认可以带新的确认时刻；它们不改变已选版本与来源。
        return selection.toBuilder().clearConfirmedAt().setTarget(selection.getTarget().toBuilder()
                .clearExpectedTargetGenerationId()).build();
    }
}
