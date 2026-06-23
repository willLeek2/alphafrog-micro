package world.willfrog.agentlangchain.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;

import java.nio.file.Path;

/**
 * workspace dump 编排服务。
 *
 * <p>流程：load run → resolve path → collect assets → verify health → write files。
 * 单次同步执行（不阻塞 caller 太多）；异步入口在 WorkspaceDumpScheduler。</p>
 *
 * <h3>调用方向</h3>
 * <p>被 {@link WorkspaceDumpScheduler} 同步调用；本类不应反向注入
 * {@link WorkspaceDumpScheduler}，避免循环依赖。</p>
 *
 * <h3>conservative 分支</h3>
 * <p>EXPIRED 状态保守分支：消息/event 缺失时只写 workspace_state.json + 有限 meta，
 * 不抛错（dump 失败不应阻塞主流程收尾）。</p>
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
     * <p>完整实现：load run → resolve dir → collect → verify → write。异常向上抛，
     * 由 {@link WorkspaceDumpScheduler} 负责重试 + DLQ。</p>
     *
     * @param runId       run 主键
     * @param conservative true = EXPIRED 保守分支（缺消息/event 时只写状态 + 有限 meta）
     * @throws Exception dump 过程任何 IO / 序列化失败
     */
    public void dumpRun(String runId, boolean conservative) throws Exception {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            throw new IllegalStateException("run 不存在: " + runId);
        }
        long uid;
        try {
            uid = Long.parseLong(run.getUserId());
        } catch (Exception e) {
            throw new IllegalStateException("run.userId 解析失败: " + run.getUserId(), e);
        }
        // 使用 _by_run_id_index 模式（无 username 时也能 resolve），便于终态 run 补偿扫描。
        Path runDir = pathResolver.runBaseDir(runId);
        CollectedAssets assets = collector.collectWorkspaceAssets(run);
        WorkspaceHealth health = verifier.verify(assets);
        log.info("Workspace dump starting: runId={} userId={} conservative={} datasetIds={} pythonScripts={}",
                runId, uid, conservative, assets.datasetIds().size(), assets.pythonScripts().size());
        try {
            writer.writeAll(runDir, run, assets, health);
        } catch (Exception e) {
            if (conservative) {
                log.warn("Workspace dump conservative path partial-fail: runId={} err={}", runId, e.getMessage(), e);
                return;
            }
            throw e;
        }
    }
}
