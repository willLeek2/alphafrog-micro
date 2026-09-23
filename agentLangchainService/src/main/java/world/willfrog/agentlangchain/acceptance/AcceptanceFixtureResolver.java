package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.OffsetDateTime;
import java.util.Optional;

import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 按 Run 把夹具查回来。
 *
 * <p>编号从 Run 的 {@code ext.context_json} 里读（创建时随请求上下文一起存下来的），泳道与代际取
 * Run 上的那两个字段，并要求它们与本进程的部署身份一致；查回来的行逐条核对「还在、已启用、
 * 没过期」。任何一条不满足都当场报错，不悄悄当普通 Run 跑。ext 读不回来、或者读出来分不清这条 Run
 * 到底是不是夹具 Run，同样当场报错：那种情况下放它过去，一次本该跑夹具的验收会连上真实模型。</p>
 *
 * <p>模型回复与结果放行策略都走这一份实现：两边对「这条 Run 带的是哪条夹具、现在还能不能用」
 * 必须有同一个答案，各写一套迟早会分家。</p>
 *
 * <p>夹具 Run 每一次取用都重新查一遍、重新核一遍：中途被停用或过期时，后续执行按失败处理并写明
 * 原因。控制 Run 第一次读过放行策略之后，启用与过期随内容一起冻结，后续只把行读回来对策略摘要；
 * 那条路径见 {@link AcceptanceRunPolicyRegistry}。</p>
 */
@Component
@Slf4j
public class AcceptanceFixtureResolver {

    private final AcceptanceFixtureStore store;
    private final DeploymentIdentityProvider identityProvider;
    private final ObjectMapper objectMapper;

    public AcceptanceFixtureResolver(AcceptanceFixtureStore store,
                                     DeploymentIdentityProvider identityProvider,
                                     ObjectMapper objectMapper) {
        this.store = store;
        this.identityProvider = identityProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 这条 Run 现在能用哪一行夹具。
     *
     * @param run 正在执行的 Run
     * @return 不带夹具编号的 Run 返回空（照原样走普通路径）；带编号的返回夹具当前的样子
     * @throws AcceptanceFixtureExecutionException 带了编号但夹具不能用、或者本进程认不出这条 Run 的泳道代际
     */
    public Optional<AcceptanceFixtureRow> resolve(AgentRun run) {
        if (run == null || isBlank(run.getId())) {
            return Optional.empty();
        }
        // 先解析 ext 与请求上下文，再看有没有夹具编号：不看「文本里有没有出现过这个词」。
        // 上下文损坏到字段名不完整时，字面判断会放过它，这条 Run 就会接上真实模型跑完，
        // 而验收最不能有的结果就是「本该失败的场景看起来跑过了」。
        String contextJson = contextJsonOf(run);
        if (contextJson == null) {
            return Optional.empty();
        }
        JsonNode context = readContext(contextJson);
        if (context.get(AcceptanceFixtureGate.CONTEXT_FIELD) == null
                || context.get(AcceptanceFixtureGate.CONTEXT_FIELD).isNull()) {
            // 上下文读得出来、里面确实没有夹具编号：这条 Run 就是普通 Run
            //（正文里提到过这个词不算，正文不在请求上下文里）。
            return Optional.empty();
        }
        DeploymentIdentity lane = laneOf(run, "acceptance_fixture_lane_unknown",
                "acceptance_fixture_lane_mismatch");
        String fixtureId = readFixtureId(context);
        return Optional.of(requireUsableRow(lane, fixtureId, run.getId(), "acceptance_fixture"));
    }

    /**
     * 这条 Run 现在能用哪一行控制行（同一张夹具表，编号来自 {@code acceptanceControlId}）。
     *
     * <p>请求上下文里已经带了 {@code acceptanceFixtureId} 时返回空：放行策略仍走夹具那一条，
     * 不把两行的策略混在一起。控制编号是空的或读不出来时当场拒绝，不悄悄当普通 Run 跑。</p>
     */
    public Optional<AcceptanceFixtureRow> resolveControl(AgentRun run) {
        return resolveControlRow(run, true);
    }

    /**
     * 控制 Run 已经冻结过策略内容之后，再把这一行读回来对摘要。
     *
     * <p>行还在就能读。启用、过期不再挡：TTL 到期或中途停用时，压住的成员仍按冻结的那一版等到
     * 放行点或兜底。内容摘要对不上仍由调用方拒绝。行被删掉仍是 {@code acceptance_control_not_found}。</p>
     */
    public Optional<AcceptanceFixtureRow> resolveControlIgnoringLiveness(AgentRun run) {
        return resolveControlRow(run, false);
    }

    private Optional<AcceptanceFixtureRow> resolveControlRow(AgentRun run, boolean requireLive) {
        if (run == null || isBlank(run.getId())) {
            return Optional.empty();
        }
        String contextJson = contextJsonOf(run);
        if (contextJson == null) {
            return Optional.empty();
        }
        JsonNode context = readContext(contextJson);
        if (context.get(AcceptanceFixtureGate.CONTEXT_FIELD) != null
                && !context.get(AcceptanceFixtureGate.CONTEXT_FIELD).isNull()) {
            return Optional.empty();
        }
        if (context.get(AcceptanceControlGate.CONTEXT_FIELD) == null
                || context.get(AcceptanceControlGate.CONTEXT_FIELD).isNull()) {
            return Optional.empty();
        }
        DeploymentIdentity lane = laneOf(run, "acceptance_control_lane_unknown",
                "acceptance_control_lane_mismatch");
        String controlId = readControlId(context);
        if (requireLive) {
            return Optional.of(requireUsableRow(lane, controlId, run.getId(), "acceptance_control"));
        }
        return Optional.of(loadExistingRow(lane, controlId, run.getId(), "acceptance_control"));
    }

    /**
     * 本进程与这条 Run 的泳道代际，两边一致才认。
     *
     * <p>不一致说明这条 Run 不属于当前构建：夹具按泳道与代际发布，拿另一份构建的身份去查，
     * 查到的内容与这次执行没有关系。</p>
     */
    private DeploymentIdentity laneOf(AgentRun run, String laneUnknownCode, String laneMismatchCode) {
        DeploymentIdentity stored;
        try {
            stored = new DeploymentIdentity(run.getDeploymentId(), run.getDeploymentGenerationId());
        } catch (RuntimeException e) {
            throw refuse(laneUnknownCode,
                    "这条 Run 的部署身份读不出来（" + e.getMessage() + "），认不出它在哪个泳道哪个代际下跑");
        }
        DeploymentIdentity local;
        try {
            local = identityProvider.current();
        } catch (RuntimeException e) {
            throw refuse(laneUnknownCode,
                    "本进程的部署身份读不出来（" + e.getMessage() + "），带编号的 Run 不能这样执行");
        }
        if (!local.equals(stored)) {
            throw refuse(laneMismatchCode,
                    "这条 Run 属于 lane=" + stored.deploymentId() + " generation=" + stored.generationId()
                            + "，本进程是 lane=" + local.deploymentId() + " generation=" + local.generationId()
                            + "：夹具只在本泳道本代际下生效");
        }
        return stored;
    }

    private AcceptanceFixtureRow loadExistingRow(DeploymentIdentity lane,
                                                 String rowId,
                                                 String runId,
                                                 String codePrefix) {
        return store.find(lane.deploymentId(), lane.generationId(), rowId)
                .orElseThrow(() -> refuse(codePrefix + "_not_found",
                        "本泳道本代际没有这一行: id=" + rowId
                                + " lane=" + lane.deploymentId() + " generation=" + lane.generationId()
                                + " runId=" + runId));
    }

    private AcceptanceFixtureRow requireUsableRow(DeploymentIdentity lane,
                                                  String rowId,
                                                  String runId,
                                                  String codePrefix) {
        AcceptanceFixtureRow row = loadExistingRow(lane, rowId, runId, codePrefix);
        if (!row.enabled()) {
            throw refuse(codePrefix + "_not_enabled", "这一行还没启用: " + row.describe());
        }
        if (row.expiredAt(OffsetDateTime.now())) {
            throw refuse(codePrefix + "_expired", "这一行授权已过期: " + row.describe());
        }
        return row;
    }

    /**
     * 取 Run 的 ext 里原样存着的请求上下文。
     *
     * <p>创建路径把请求上下文单独存成一个字符串（见 {@code AgentRunEventService} 的 ext 组装），
     * 所以只有「ext 读得出来、里面根本没有 context_json 这个字段」才算普通 Run：ext 里还存着用户
     * 消息正文，正文里提到夹具编号这个词，不代表这是一条夹具 Run。</p>
     *
     * <p>读不回来的情况一律拒绝，不按普通 Run 往下跑：ext 读不回来、或者读出来不是对象、或者
     * context_json 不是一个字符串，都说明这条 Run 的「是不是夹具 Run」判不出来。判不出来时按普通 Run
     * 放过去，会让一次本该跑夹具的验收悄悄连上真实模型——那种结果看起来像跑过了，验收最不能有的就是
     * 这个。这条口径与受理时的夹具门一致：那里遇到读不回来的上下文也是当场报错，不放过去。</p>
     */
    private String contextJsonOf(AgentRun run) {
        JsonNode root;
        try {
            root = objectMapper.readTree(run.getExt());
        } catch (Exception e) {
            throw refuse("acceptance_fixture_invalid",
                    "这条 Run 的 ext 读不回来（" + e.getMessage() + "）：分不清它是夹具 Run 还是普通 Run，"
                            + "按夹具这一侧拒绝，不按普通 Run 跑");
        }
        if (root == null || !root.isObject()) {
            throw refuse("acceptance_fixture_invalid",
                    "这条 Run 的 ext 不是一个 JSON 对象：分不清它是夹具 Run 还是普通 Run，"
                            + "按夹具这一侧拒绝，不按普通 Run 跑");
        }
        JsonNode context = root.get("context_json");
        if (context == null || context.isNull()) {
            // ext 里没有这个字段：这条 Run 只是正文里提到了这个词，照普通 Run 处理。
            return null;
        }
        if (!context.isTextual()) {
            throw refuse("acceptance_fixture_invalid",
                    "这条 Run 的 ext 里带 context_json，但它不是一个字符串（是 "
                            + context.getNodeType() + "）：夹具编号就写在这个字符串里，读不出来就分不清"
                            + "这条 Run 是不是夹具 Run");
        }
        return context.asText().isBlank() ? null : context.asText();
    }

    /**
     * 读请求上下文。
     *
     * <p>读不出来就拒绝：只有「读得出来、里面确实没有夹具编号」才算普通 Run。内容损坏时靠文本里
     * 有没有出现字段名是猜的——截断、转义变化都可能让字段名不完整，一次本该跑夹具的验收会因此
     * 接上真实模型。</p>
     */
    private JsonNode readContext(String contextJson) {
        JsonNode context;
        try {
            context = objectMapper.readTree(contextJson);
        } catch (Exception e) {
            throw refuse("acceptance_fixture_invalid",
                    "这条 Run 的请求上下文不是合法 JSON（" + e.getMessage() + "）：分不清它是夹具 Run 还是"
                            + "普通 Run，按夹具这一侧拒绝，不按普通 Run 跑");
        }
        if (context == null || !context.isObject()) {
            throw refuse("acceptance_fixture_invalid",
                    "这条 Run 的请求上下文不是一个 JSON 对象：分不清它是夹具 Run 还是普通 Run，"
                            + "按夹具这一侧拒绝，不按普通 Run 跑");
        }
        return context;
    }

    /** 上下文里写了夹具编号就要读出来；读不出来按不可用处理，不悄悄当普通 Run 跑。 */
    private String readFixtureId(JsonNode contextRoot) {
        JsonNode value = contextRoot.get(AcceptanceFixtureGate.CONTEXT_FIELD);
        if (value == null || value.isNull() || !value.isValueNode()) {
            throw refuse("acceptance_fixture_invalid",
                    "请求上下文里的 " + AcceptanceFixtureGate.CONTEXT_FIELD + " 值读不出来");
        }
        String fixtureId = value.asText("").trim();
        if (fixtureId.isBlank()) {
            throw refuse("acceptance_fixture_invalid",
                    "请求上下文里的 " + AcceptanceFixtureGate.CONTEXT_FIELD + " 是空的");
        }
        return fixtureId;
    }

    /** 上下文里写了控制编号就要读出来；读不出来按不可用处理，不悄悄当普通 Run 跑。 */
    private String readControlId(JsonNode contextRoot) {
        JsonNode value = contextRoot.get(AcceptanceControlGate.CONTEXT_FIELD);
        if (value == null || value.isNull() || !value.isValueNode()) {
            throw refuse("acceptance_control_invalid",
                    "请求上下文里的 " + AcceptanceControlGate.CONTEXT_FIELD + " 值读不出来");
        }
        String controlId = value.asText("").trim();
        if (controlId.isBlank()) {
            throw refuse("acceptance_control_invalid",
                    "请求上下文里的 " + AcceptanceControlGate.CONTEXT_FIELD + " 是空的");
        }
        return controlId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
