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
 * 没过期」。任何一条不满足都当场报错，不悄悄当普通 Run 跑。</p>
 *
 * <p>模型回复与结果放行策略都走这一份实现：两边对「这条 Run 带的是哪条夹具、现在还能不能用」
 * 必须有同一个答案，各写一套迟早会分家。</p>
 *
 * <p>每一次取用都重新查一遍、重新核一遍：夹具在跑的中途被停用或过期时，这条 Run 的后续执行按
 * 失败处理并写明原因。</p>
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
        if (run == null || isBlank(run.getId()) || !mentionsFixture(run.getExt())) {
            return Optional.empty();
        }
        String contextJson = contextJsonOf(run);
        if (contextJson == null || !contextJson.contains(AcceptanceFixtureGate.CONTEXT_FIELD)) {
            // ext 里出现这个词，只是因为别处提到了它（例如用户消息正文里写了这个词）。
            // 请求上下文里没有这个字段，这条 Run 就是普通 Run。
            return Optional.empty();
        }
        DeploymentIdentity lane = laneOf(run);
        String fixtureId = readFixtureId(contextJson);
        return Optional.of(requireUsableRow(lane, fixtureId, run.getId()));
    }

    /**
     * 本进程与这条 Run 的泳道代际，两边一致才认。
     *
     * <p>不一致说明这条 Run 不属于当前构建：夹具按泳道与代际发布，拿另一份构建的身份去查，
     * 查到的内容与这次执行没有关系。</p>
     */
    private DeploymentIdentity laneOf(AgentRun run) {
        DeploymentIdentity stored;
        try {
            stored = new DeploymentIdentity(run.getDeploymentId(), run.getDeploymentGenerationId());
        } catch (RuntimeException e) {
            throw refuse("acceptance_fixture_lane_unknown",
                    "这条 Run 的部署身份读不出来（" + e.getMessage() + "），认不出它在哪个泳道哪个代际下跑");
        }
        DeploymentIdentity local;
        try {
            local = identityProvider.current();
        } catch (RuntimeException e) {
            throw refuse("acceptance_fixture_lane_unknown",
                    "本进程的部署身份读不出来（" + e.getMessage() + "），带夹具编号的 Run 不能这样执行");
        }
        if (!local.equals(stored)) {
            throw refuse("acceptance_fixture_lane_mismatch",
                    "这条 Run 属于 lane=" + stored.deploymentId() + " generation=" + stored.generationId()
                            + "，本进程是 lane=" + local.deploymentId() + " generation=" + local.generationId()
                            + "：夹具只在本泳道本代际下生效");
        }
        return stored;
    }

    private AcceptanceFixtureRow requireUsableRow(DeploymentIdentity lane, String fixtureId, String runId) {
        AcceptanceFixtureRow row = store.find(lane.deploymentId(), lane.generationId(), fixtureId)
                .orElseThrow(() -> refuse("acceptance_fixture_not_found",
                        "本泳道本代际没有这条夹具: fixture=" + fixtureId
                                + " lane=" + lane.deploymentId() + " generation=" + lane.generationId()
                                + " runId=" + runId));
        if (!row.enabled()) {
            throw refuse("acceptance_fixture_not_enabled", "夹具还没启用: " + row.describe());
        }
        if (row.expiredAt(OffsetDateTime.now())) {
            throw refuse("acceptance_fixture_expired", "夹具授权已过期: " + row.describe());
        }
        return row;
    }

    /** 取 Run 的 ext 里原样存着的请求上下文；拿不到就是空（当成普通 Run 处理）。 */
    private String contextJsonOf(AgentRun run) {
        try {
            JsonNode root = objectMapper.readTree(run.getExt());
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode context = root.get("context_json");
            return context != null && context.isTextual() && !context.asText().isBlank()
                    ? context.asText() : null;
        } catch (Exception e) {
            log.warn("Run 的 ext 读不回来，按普通 Run 处理: runId={} reason={}", run.getId(), e.getMessage());
            return null;
        }
    }

    /** 上下文里写了夹具编号就要读出来；读不出来按不可用处理，不悄悄当普通 Run 跑。 */
    private String readFixtureId(String contextJson) {
        JsonNode contextRoot;
        try {
            contextRoot = objectMapper.readTree(contextJson);
        } catch (Exception e) {
            throw refuse("acceptance_fixture_invalid",
                    "请求上下文里有 " + AcceptanceFixtureGate.CONTEXT_FIELD + "，但整体不是合法 JSON: "
                            + e.getMessage());
        }
        if (contextRoot == null || !contextRoot.isObject()) {
            throw refuse("acceptance_fixture_invalid",
                    "请求上下文里有 " + AcceptanceFixtureGate.CONTEXT_FIELD + " 这个词，但整体不是 JSON 对象");
        }
        JsonNode value = contextRoot.get(AcceptanceFixtureGate.CONTEXT_FIELD);
        if (value == null || value.isNull() || !value.isValueNode()) {
            throw refuse("acceptance_fixture_invalid",
                    "请求上下文里有 " + AcceptanceFixtureGate.CONTEXT_FIELD + " 这个词，但值读不出来");
        }
        String fixtureId = value.asText("").trim();
        if (fixtureId.isBlank()) {
            throw refuse("acceptance_fixture_invalid",
                    "请求上下文里的 " + AcceptanceFixtureGate.CONTEXT_FIELD + " 是空的");
        }
        return fixtureId;
    }

    /**
     * ext 里有没有可能带夹具。
     *
     * <p>先做一次廉价判断，让普通 Run 一次解析都不做；判断过了还要再看请求上下文里到底有没有这个
     * 字段，因为 ext 里还存着用户消息正文，正文提到这个词不代表这是夹具 Run。</p>
     */
    private boolean mentionsFixture(String ext) {
        return ext != null && ext.contains(AcceptanceFixtureGate.CONTEXT_FIELD);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
