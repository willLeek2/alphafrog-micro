package world.willfrog.agentlangchain.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;

import java.nio.file.Path;
import java.util.Optional;

/**
 * workspace dump 编排服务。
 *
 * <p>流程：load run → resolve path → collect assets → verify health →
 * fingerprint skip 判定 → write files。单次同步执行（不阻塞 caller 太多）；
 * 异步入口在 WorkspaceDumpScheduler。</p>
 *
 * <h3>调用方向</h3>
 * <p>被 {@link WorkspaceDumpScheduler} 同步调用；本类不应反向注入
 * {@link WorkspaceDumpScheduler}，避免循环依赖。</p>
 *
 * <h3>D21-A：fingerprint skip（事件/轮询两路共用）</h3>
 * <p>dump 前读回既有 workspace_state.json：指纹一致且上次 dump 完整
 * （mode=full 且 brokenRefsCount=0）时整段 skip，不重写任何文件；
 * 指纹变化、上次不完整、legacy 状态（缺 mode/brokenRefsCount）或状态损坏时重 dump。
 * 指纹计算统一走 {@link WorkspaceFingerprints}，与写入侧一致。
 * 事件路径与轮询路径都经本入口，因此重复触发不会放大写入。</p>
 *
 * <h3>D21-A：EXPIRED 真 conservative</h3>
 * <p>conservative=true 时走减量写入（只写 workspace_state.json + 有限 meta.json）；
 * 收集/校验失败降级为空资产但不中断；任何写失败仍向上抛，由
 * {@link WorkspaceDumpScheduler} 重试/DLQ 兜底——不再静默吞错。</p>
 *
 * @author wang
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceDumpService {

    private final AgentRunMapper runMapper;
    private final WorkspacePathResolver pathResolver;
    private final WorkspaceAssetCollector collector;
    private final WorkspaceHealthVerifier verifier;
    private final WorkspaceManifestWriter writer;

    /**
     * 执行单次 run workspace dump。
     *
     * <p>完整实现：load run → resolve dir → collect → verify → fingerprint skip 判定 → write。
     * 异常向上抛，由 {@link WorkspaceDumpScheduler} 负责重试 + DLQ。</p>
     *
     * @param runId       run 主键
     * @param conservative true = EXPIRED 保守分支（减量写入：仅状态 + 有限 meta）
     * @throws Exception dump 过程任何 IO / 序列化失败（conservative 写失败同样上抛）
     */
    public void dumpRun(String runId, boolean conservative) throws Exception {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            throw new IllegalStateException("run 不存在: " + runId);
        }
        // userId 只用于日志；解析失败降级为原样打印，不阻塞 dump（此前抛错会让
        // 非数值 userId 的 run 永久占用重试/DLQ，且 runDir 寻址不依赖 uid）。
        String uidForLog;
        try {
            uidForLog = String.valueOf(Long.parseLong(run.getUserId()));
        } catch (Exception e) {
            uidForLog = run.getUserId() + "(unparseable)";
        }
        // 使用 _by_run_id_index 模式（无 username 时也能 resolve），便于终态 run 补偿扫描。
        Path runDir = pathResolver.runBaseDir(runId);
        if (conservative) {
            dumpConservative(runId, uidForLog, run, runDir);
            return;
        }
        CollectedAssets assets = collector.collectWorkspaceAssets(run);
        WorkspaceHealth health = verifier.verify(assets);
        String fingerprint = WorkspaceFingerprints.compute(run, assets.messages());
        if (shouldSkip(writer.readWorkspaceState(runDir), fingerprint, false)) {
            log.info("Workspace dump skipped (fingerprint match + complete): runId={} userId={} fingerprint={}",
                    runId, uidForLog, fingerprint);
            return;
        }
        log.info("Workspace dump starting: runId={} userId={} conservative={} datasetIds={} pythonScripts={}",
                runId, uidForLog, false, assets.datasetIds().size(), assets.pythonScripts().size());
        writer.writeAll(runDir, run, assets, health);
    }

    /**
     * EXPIRED 保守分支：减量采集 + 减量写入（D21-A）。
     *
     * <p>采集/校验失败时降级为空资产（warn 记录，不静默）：保守分支的意义正是在
     * 消息/event 缺失时仍能落最小状态。写失败则上抛，进入 scheduler 重试/DLQ。</p>
     */
    private void dumpConservative(String runId, String uidForLog, AgentRun run, Path runDir) throws Exception {
        CollectedAssets assets = null;
        WorkspaceHealth health = null;
        try {
            assets = collector.collectWorkspaceAssets(run);
            health = verifier.verify(assets);
        } catch (Exception e) {
            log.warn("Workspace dump conservative collection degraded: runId={} err={}",
                    runId, e.getMessage(), e);
        }
        String fingerprint = WorkspaceFingerprints.compute(run, assets == null ? null : assets.messages());
        if (shouldSkip(writer.readWorkspaceState(runDir), fingerprint, true)) {
            log.info("Workspace dump skipped (conservative, fingerprint match): runId={} userId={} fingerprint={}",
                    runId, uidForLog, fingerprint);
            return;
        }
        log.info("Workspace dump starting: runId={} userId={} conservative=true datasetIds={} pythonScripts={}",
                runId, uidForLog,
                assets == null || assets.datasetIds() == null ? 0 : assets.datasetIds().size(),
                assets == null || assets.pythonScripts() == null ? 0 : assets.pythonScripts().size());
        writer.writeConservative(runDir, run, assets, health);
    }

    /**
     * dump 前 skip 判定（D21-A）：仅当既有状态可读、指纹一致且"至少与本次请求同样完整"时跳过。
     *
     * <ul>
     *   <li>full 触发：上次必须是 mode=full 且 brokenRefsCount=0（完整才允许 skip；
     *       上次有 brokenRefs 时重 dump，给 dataset 补齐后的自愈机会）。</li>
     *   <li>conservative 触发：上次 mode=full 或 conservative 均可（既有状态已覆盖
     *       减量写入的信息量）。</li>
     *   <li>legacy 状态（mode 缺失）、状态损坏、指纹不一致：永不 skip，重 dump 收敛。</li>
     * </ul>
     *
     * <p>包私有 + static：供单元测试直接钉住判定表。</p>
     */
    static boolean shouldSkip(Optional<WorkspaceManifestWriter.WorkspaceState> existing,
                              String fingerprint, boolean conservative) {
        if (existing == null || existing.isEmpty() || fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        WorkspaceManifestWriter.WorkspaceState state = existing.get();
        if (!fingerprint.equals(state.fingerprint())) {
            return false;
        }
        if (conservative) {
            return WorkspaceManifestWriter.MODE_FULL.equals(state.mode())
                    || WorkspaceManifestWriter.MODE_CONSERVATIVE.equals(state.mode());
        }
        return WorkspaceManifestWriter.MODE_FULL.equals(state.mode())
                && state.brokenRefsCount() != null
                && state.brokenRefsCount() == 0;
    }
}
