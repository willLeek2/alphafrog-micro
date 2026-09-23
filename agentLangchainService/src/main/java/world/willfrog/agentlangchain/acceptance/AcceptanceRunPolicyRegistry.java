package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 Run 取回「这条 Run 的结果放行策略」。
 *
 * <p>策略来自夹具或控制行的 {@code dispatch_policy_json}：不带这两种编号的 Run、或者这一行没写
 * 放行策略的 Run 拿到空，成员结果照原来的方式立刻收尾。夹具本身由 {@link AcceptanceFixtureResolver}
 * 查回来并核对，所以这一行在跑的中途失效时，读策略这一步会跟着停下——不会出现「夹具不让用了，
 * 但压住的成员还被悄悄放过去」。请求同时带了夹具编号和控制编号时，策略仍取夹具那一行。</p>
 *
 * <p>「读到的是策略」与「读到的是空」都要冻结：第一次读到的那一版是什么，这条 Run 就按哪一版跑完。
 * 只冻结有策略的那一种会漏掉一种改法——夹具一开始没写策略、跑到一半被改成带策略，于是前半段的
 * 成员结果当场收尾、后半段按新策略压住或判失败，一次验收说不清它按哪一版跑完。</p>
 */
@Component
@Slf4j
public class AcceptanceRunPolicyRegistry {

    /** 进程里最多同时记住这么多条 Run 的策略；到顶时拒绝新的夹具 Run，不去挤掉正在跑的那些。 */
    static final int MAX_TRACKED_RUNS = 128;

    private final AcceptanceFixtureResolver fixtureResolver;
    private final ObjectMapper objectMapper;
    private final FixtureRuleHitStore ruleHitStore;
    private final Map<String, AcceptanceReleasePolicy> policyByRun = new ConcurrentHashMap<>();
    private final Map<String, String> fixtureIdByRun = new ConcurrentHashMap<>();
    /** 一开始读到「没有放行策略」的 Run：这个状态也要冻结，之后夹具加了策略就停下。 */
    private final Set<String> absentRuns = ConcurrentHashMap.newKeySet();

    public AcceptanceRunPolicyRegistry(AcceptanceFixtureResolver fixtureResolver,
                                       ObjectMapper objectMapper,
                                       FixtureRuleHitStore ruleHitStore) {
        this.fixtureResolver = fixtureResolver;
        this.objectMapper = objectMapper;
        this.ruleHitStore = ruleHitStore;
    }

    /**
     * 这条 Run 的结果放行策略。
     *
     * @param run 正在执行的 Run
     * @return 不带夹具编号、或者这条 Run 一开始就没有放行策略时返回空
     * @throws AcceptanceFixtureExecutionException 带了编号但夹具现在不能用、策略读不出来、或者内容
     *                                             在跑的过程中被改过（包括「从没有策略改成有策略」）
     */
    public Optional<AcceptanceReleasePolicy> policyForRun(AgentRun run) {
        Optional<AcceptanceFixtureRow> row = fixtureResolver.resolve(run);
        if (row.isEmpty()) {
            row = fixtureResolver.resolveControl(run);
        }
        if (row.isEmpty()) {
            return Optional.empty();
        }
        String runId = run.getId();
        String fixtureId = row.get().fixtureId();
        String knownFixtureId = fixtureIdByRun.get(runId);
        if (knownFixtureId != null && !knownFixtureId.equals(fixtureId)) {
            throw AcceptanceFixtureExecutionException.refuse("acceptance_fixture_identity_changed",
                    "这条 Run 一开始用的是夹具 " + knownFixtureId + "，现在请求上下文里写着 "
                            + fixtureId + "：同一条 Run 的夹具身份不许中途换");
        }
        AcceptanceReleasePolicy cached = policyByRun.get(runId);
        if (cached != null) {
            // 缓存命中也要跟库里现在那一版比一次摘要：夹具行在运行途中被原位改过时，进程里继续按
            // 旧策略跑，库里记下来的却是另一版的内容与执行记录对不上。策略原文很小，重读一次比
            //「一次验收说不清用的是哪一版」划算。
            requireUnchanged(cached, fixtureId, row.get().scenarioId(), row.get().dispatchPolicyJson());
            return Optional.of(cached);
        }
        if (absentRuns.contains(runId)) {
            // 「一开始就没有策略」也是一种冻结状态：后来加了策略就要停下，不能前半段当场收尾、
            // 后半段按新策略压住成员。
            requireStillAbsent(fixtureId, row.get().scenarioId(), row.get().dispatchPolicyJson());
            return Optional.empty();
        }
        Optional<AcceptanceReleasePolicy> parsed =
                AcceptanceReleasePolicy.parse(fixtureId, row.get().dispatchPolicyJson(), objectMapper);
        if (parsed.isEmpty()) {
            if (trackedRunCount() >= MAX_TRACKED_RUNS) {
                throw AcceptanceFixtureExecutionException.refuse("acceptance_fixture_tracked_runs_full",
                        "本进程记住的夹具 Run 已经到 " + MAX_TRACKED_RUNS + " 条：这个数只会在终态事件漏掉时涨起来，"
                                + "先查这些 Run 为什么没走到终态");
            }
            ruleHitStore.snapshotPolicyAbsent(runId, fixtureId, row.get().scenarioId());
            fixtureIdByRun.putIfAbsent(runId, fixtureId);
            absentRuns.add(runId);
            log.info("这条 Run 没有放行策略，按「没有策略」冻结: runId={} fixture={} scenario={}",
                    runId, fixtureId, row.get().scenarioId());
            return Optional.empty();
        }
        if (trackedRunCount() >= MAX_TRACKED_RUNS) {
            throw AcceptanceFixtureExecutionException.refuse("acceptance_fixture_tracked_runs_full",
                    "本进程记住的夹具 Run 已经到 " + MAX_TRACKED_RUNS + " 条：这个数只会在终态事件漏掉时涨起来，"
                            + "先查这些 Run 为什么没走到终态");
        }
        // 第一次读到策略就把这份策略写下来：之后夹具行被改过、被停用或被删掉，核对结论仍然说得清
        //「这次验收点名了哪几件事」。内容被原位改过时这里会当场拒绝。
        ruleHitStore.snapshotPolicy(runId, fixtureId, row.get().scenarioId(), parsed.get());
        fixtureIdByRun.putIfAbsent(runId, fixtureId);
        AcceptanceReleasePolicy winner = policyByRun.putIfAbsent(runId, parsed.get());
        log.info("验收夹具的放行策略生效: runId={} fixture={} scenario={} 规则={} 条 策略摘要={}",
                runId, fixtureId, row.get().scenarioId(), parsed.get().ruleCount(), parsed.get().digest());
        return Optional.of(winner == null ? parsed.get() : winner);
    }

    /**
     * 缓存里的策略与库里现在那一版比一次摘要。
     *
     * <p>对不上说明夹具行在运行途中被原位改过：这条 Run 已经按旧策略走过一段（压住过谁、放行过谁），
     * 换一版接着跑会让执行记录与证据对不上。当场拒绝，不悄悄换一版。</p>
     */
    private void requireUnchanged(AcceptanceReleasePolicy cached,
                                  String fixtureId,
                                  String scenarioId,
                                  String policyJson) {
        Optional<AcceptanceReleasePolicy> current =
                AcceptanceReleasePolicy.parse(fixtureId, policyJson, objectMapper);
        String currentDigest = current.map(AcceptanceReleasePolicy::digest).orElse(null);
        if (currentDigest == null || !currentDigest.equals(cached.digest())) {
            throw AcceptanceFixtureExecutionException.refuse("acceptance_fixture_content_changed",
                    "这条 Run 一开始用的是策略摘要 " + cached.digest() + "（夹具 " + fixtureId
                            + "，场景 " + scenarioId + "），现在库里那一版是 "
                            + (currentDigest == null ? "空（策略被删掉了）" : currentDigest)
                            + "：同一条 Run 跑的过程中夹具的放行策略被改过，这一次验收说不清用的是哪一版");
        }
    }

    /**
     * 「一开始就没有策略」的那条 Run 之后又读到了策略：停下。
     *
     * <p>前半段的成员结果是当场收尾的（没有策略可依），后半段按新加的策略压住或判失败，
     * 这一次验收就说不清它到底按哪一版跑完。</p>
     */
    private void requireStillAbsent(String fixtureId, String scenarioId, String policyJson) {
        Optional<AcceptanceReleasePolicy> current =
                AcceptanceReleasePolicy.parse(fixtureId, policyJson, objectMapper);
        if (current.isPresent()) {
            throw AcceptanceFixtureExecutionException.refuse("acceptance_fixture_content_changed",
                    "这条 Run 一开始的放行策略是空的（夹具 " + fixtureId + "，场景 " + scenarioId
                            + "），现在读到了 " + current.get().ruleCount() + " 条规则（摘要 "
                            + current.get().digest()
                            + "）：同一条 Run 跑的过程中夹具新加了放行策略，这一次验收说不清用的是哪一版");
        }
    }

    /** 本进程记住的夹具 Run 条数：有策略的与「一开始就没有策略」的合起来算同一份上限。 */
    private int trackedRunCount() {
        return policyByRun.size() + absentRuns.size();
    }

    /** 放掉一条 Run 的策略；没有这条 Run 就是空动作。 */
    public void evict(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        fixtureIdByRun.remove(runId);
        policyByRun.remove(runId);
        absentRuns.remove(runId);
    }

    /** Run 走到终态就把策略放掉，与模型脚本位置同一个时机。 */
    @EventListener
    public void onRunFinalized(AgentRunFinalizedEvent event) {
        if (event != null) {
            evict(event.runId());
        }
    }
}
