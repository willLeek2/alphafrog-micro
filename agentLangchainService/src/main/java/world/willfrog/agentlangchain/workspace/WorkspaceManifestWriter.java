package world.willfrog.agentlangchain.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.service.AgentArtifactService.PythonScript;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * workspace 文件写入器。
 *
 * <p>职责：写 meta.json / manifest.json / conversation.jsonl / python_scripts.jsonl /
 * workspace_state.json。所有文件用 temp write → atomic rename 防 crash 半截。</p>
 *
 * <h3>fingerprint</h3>
 * <p>workspace_state.json.fingerprint = (sourceRunCompletedAt, sourceRunUpdatedAt, lastMessageSeq)；
 * 计算统一走 {@link WorkspaceFingerprints}（写入侧与 skip 判定侧共用，D21-A）。
 * 一致且上次 dump 完整（mode=full 且 brokenRefsCount=0）时 skip，变化或不完整时覆盖重 dump。</p>
 *
 * <h3>D21-A：mode 与 conservative 减量写入</h3>
 * <p>workspace_state.json/meta.json 记录 {@code mode}（{@link #MODE_FULL}/{@link #MODE_CONSERVATIVE}）
 * 与 {@code brokenRefsCount}，供 dump 前 skip 判定读回（{@link #readWorkspaceState}）。
 * {@link #writeConservative} 是 EXPIRED 保守分支：只写 workspace_state.json + 有限 meta.json，
 * 收集失败可降级为空资产；但写失败仍然上抛（由 scheduler 重试/DLQ 兜底），不静默吞错。</p>
 *
 * <h3>D04</h3>
 * <p>dataset refPath 不再硬编码 {@code /data/agent_datasets/} 前缀，改经统一存储门面
 * {@link AgentStoragePaths#datasetRoot()} 拼接；writeAll 入口先做
 * {@link AgentStoragePaths#verifyDumpTarget(java.nio.file.Path)} 可达性 + 归属校验
 * （§4.3：不可达/越界 → 明确失败信号，不静默写错位置）。</p>
 *
 * @author wang
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceManifestWriter {

    /** workspace_state.json / meta.json 的 mode 字段：完整 dump（五文件齐全）。 */
    public static final String MODE_FULL = "full";
    /** workspace_state.json / meta.json 的 mode 字段：EXPIRED 保守减量 dump（仅状态 + 有限 meta）。 */
    public static final String MODE_CONSERVATIVE = "conservative";

    private static final String STATE_FILE_NAME = "workspace_state.json";

    // D21-A 修复：注册 JavaTimeModule 并以 ISO-8601 字符串落盘时间字段。
    // 此前裸 ObjectMapper 序列化 OffsetDateTime（PythonScript.createdAt 等）会抛
    // InvalidDefinitionException：writePythonScriptsJsonl 逐条 warn 吞掉（脚本静默丢失），
    // writeMeta 直接失败（任何 startedAt 非空的真实 run 都无法完成 dump）。
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final AgentStoragePaths storagePaths;

    /**
     * 写入 workspace 所有文件。
     *
     * @param runDir 已 resolve 的 run 目录
     * @param run    关联 AgentRun
     * @param assets collector 收集的资产
     * @param health verifier 产出的健康状态
     * @return write 结果
     */
    public WriteResult writeAll(Path runDir, AgentRun run, CollectedAssets assets, WorkspaceHealth health) {
        if (run == null || assets == null || health == null) {
            throw new IllegalArgumentException("run / assets / health 不能为空");
        }
        if (runDir == null) {
            throw new IllegalArgumentException("runDir 不能为空");
        }
        // D04 §4.3：dump 入口可达性 + 归属校验——workspace 根不可达（挂载缺失等）
        // 或 runDir 越出配置根时显式失败，不静默写错位置。
        storagePaths.verifyDumpTarget(runDir);
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建 runDir 失败: " + runDir, e);
        }

        // 1) conversation.jsonl
        writeConversationJsonl(runDir, assets.messages());

        // 2) python_scripts.jsonl
        writePythonScriptsJsonl(runDir, assets.pythonScripts());

        // 3) manifest.json
        writeManifest(runDir, run, assets, health);

        // 4) meta.json
        writeMeta(runDir, run, health, MODE_FULL);

        // 5) workspace_state.json
        WorkspaceState state = computeWorkspaceState(run, assets, health, MODE_FULL);
        writeWorkspaceState(runDir, state);

        return new WriteResult(runDir, state.fingerprint(), health.brokenRefs().size());
    }

    /**
     * EXPIRED 保守分支的减量写入（D21-A）。
     *
     * <p>只写 workspace_state.json（mode=conservative）+ 有限 meta.json，
     * 不写 conversation.jsonl / python_scripts.jsonl / manifest.json：
     * EXPIRED run 的消息/event 可能缺失，不伪造完整性声明。</p>
     *
     * <p>失败语义：入口同样走 D04 可达性校验；任何写失败向上抛，由
     * {@link WorkspaceDumpScheduler} 重试/DLQ 兜底——conservative 指"减量写入"，
     * 不是"静默吞错"。</p>
     *
     * @param runDir 已 resolve 的 run 目录
     * @param run    关联 AgentRun
     * @param assets 收集到的资产，可为 null（收集失败降级时）
     * @param health 健康校验结果，可为 null（收集失败降级时；meta 不写 health 块）
     * @return write 结果（brokenCount 恒为 0：conservative 不做完整性声明）
     */
    public WriteResult writeConservative(Path runDir, AgentRun run, CollectedAssets assets, WorkspaceHealth health) {
        if (run == null) {
            throw new IllegalArgumentException("run 不能为空");
        }
        if (runDir == null) {
            throw new IllegalArgumentException("runDir 不能为空");
        }
        // D04 §4.3：与 full 路径同一可达性 + 归属门槛，保守分支不降低失败信号。
        storagePaths.verifyDumpTarget(runDir);
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建 runDir 失败: " + runDir, e);
        }
        WorkspaceState state = computeWorkspaceState(run, assets, health, MODE_CONSERVATIVE);
        writeWorkspaceState(runDir, state);
        writeMeta(runDir, run, health, MODE_CONSERVATIVE);
        return new WriteResult(runDir, state.fingerprint(), 0);
    }

    /**
     * 读回既有 workspace_state.json，供 dump 前 skip 判定（D21-A）。
     *
     * <p>宽容语义：文件不存在 / 不是普通文件 / 解析失败 → 返回 empty
     * （按"无既有状态"处理，触发重写而不是让 dump 失败）。</p>
     *
     * @param runDir 已 resolve 的 run 目录
     * @return 既有状态；缺失或损坏时 empty
     */
    public Optional<WorkspaceState> readWorkspaceState(Path runDir) {
        if (runDir == null) {
            return Optional.empty();
        }
        Path target = runDir.resolve(STATE_FILE_NAME);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(target.toFile(), WorkspaceState.class));
        } catch (Exception e) {
            log.warn("read workspace_state failed, treat as absent: path={} err={}", target, e.getMessage());
            return Optional.empty();
        }
    }

    private void writeConversationJsonl(Path runDir, List<AgentRunMessage> messages) {
        Path target = runDir.resolve("conversation.jsonl");
        StringBuilder sb = new StringBuilder();
        List<AgentRunMessage> safe = messages == null ? List.of() : messages;
        for (AgentRunMessage m : safe) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("seq", m.getSeq());
            obj.put("role", m.getRole());
            obj.put("msg_type", m.getMsgType());
            obj.put("content", m.getContent());
            obj.put("meta_json", m.getMetaJson());
            obj.put("created_at", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
            try {
                sb.append(MAPPER.writeValueAsString(obj)).append('\n');
            } catch (Exception e) {
                log.warn("serialize message failed: seq={} runId={}", m.getSeq(), m.getRunId(), e);
            }
        }
        atomicWrite(target, sb.toString());
    }

    private void writePythonScriptsJsonl(Path runDir, List<PythonScript> scripts) {
        Path target = runDir.resolve("python_scripts.jsonl");
        StringBuilder sb = new StringBuilder();
        List<PythonScript> safe = scripts == null ? List.of() : scripts;
        for (PythonScript script : safe) {
            try {
                sb.append(MAPPER.writeValueAsString(script)).append('\n');
            } catch (Exception e) {
                log.warn("serialize python script failed: {}", e.getMessage());
            }
        }
        atomicWrite(target, sb.toString());
    }

    private void writeManifest(Path runDir, AgentRun run, CollectedAssets assets, WorkspaceHealth health) {
        Path target = runDir.resolve("manifest.json");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", "v0");
        manifest.put("runId", run.getId());
        manifest.put("userId", run.getUserId());
        manifest.put("sanitizedUserDir", runDir.getParent() == null || runDir.getParent().getParent() == null
                ? "" : runDir.getParent().getParent().getFileName().toString());
        manifest.put("createdAt", OffsetDateTime.now().toString());
        manifest.put("extractedByService", "WorkspaceDumpService");
        manifest.put("extractedByVersion", "v0");
        manifest.put("policyScope", "user_default");

        List<Map<String, Object>> manifestAssets = new ArrayList<>();
        List<String> datasetIds = assets.datasetIds() == null ? List.of() : assets.datasetIds();
        for (String datasetId : datasetIds) {
            if (datasetId == null || datasetId.isBlank()) {
                continue;
            }
            Map<String, Object> asset = new LinkedHashMap<>();
            // 这里不实际读 manifest.json 文件，health.manifestMembers 里已经有 broken 后的状态；
            // 我们用 manifestMembers 来识别 manifest 资产
            boolean isManifest = health.manifestMembers().stream()
                    .anyMatch(m -> m.manifestId().equals(datasetId));
            asset.put("kind", isManifest ? "dataset_manifest" : "dataset");
            asset.put("assetId", datasetId);
            asset.put("mode", "reference");
            // D04：dataset 根经统一存储门面解析（原为硬编码 /data/agent_datasets/ 前缀）。
            asset.put("refPath", storagePaths.datasetRoot().resolve(datasetId).toString());
            if (isManifest) {
                List<Map<String, Object>> members = new ArrayList<>();
                for (ManifestMemberView m : health.manifestMembers()) {
                    if (!m.manifestId().equals(datasetId)) continue;
                    Map<String, Object> member = new LinkedHashMap<>();
                    member.put("tsCode", m.tsCode());
                    member.put("datasetId", m.datasetId());
                    member.put("status", m.status());
                    member.put("rowCount", m.rowCount());
                    members.add(member);
                }
                asset.put("memberCount", members.size());
                long readyCount = members.stream().filter(m -> "ready".equals(m.get("status"))).count();
                long brokenCount = members.stream().filter(m -> "broken".equals(m.get("status"))).count();
                long failedCount = members.stream().filter(m -> "failed".equals(m.get("status"))).count();
                asset.put("readyCount", readyCount);
                asset.put("brokenCount", brokenCount);
                asset.put("failedCount", failedCount);
                asset.put("members", members);
                asset.put("mode", "best-effort");  // manifest asset 走的是 fallback 路径
                asset.put("absCheck", "manifest file exists; ready members resolved under dataset.path");
            } else {
                boolean isBroken = health.brokenRefs().stream()
                        .anyMatch(b -> b.assetId().equals(datasetId));
                if (isBroken) {
                    asset.put("mode", "broken");
                }
                asset.put("absCheck", "atomic csv exists");
            }
            manifestAssets.add(asset);
        }
        List<PythonScript> pythonScripts = assets.pythonScripts() == null ? List.of() : assets.pythonScripts();
        for (PythonScript script : pythonScripts) {
            Map<String, Object> asset = new LinkedHashMap<>();
            asset.put("kind", "python_script");
            asset.put("assetId", script.ref());
            asset.put("mode", "inline");
            asset.put("inline", script);
            manifestAssets.add(asset);
        }
        manifest.put("assets", manifestAssets);

        List<Map<String, Object>> brokenRefsList = new ArrayList<>();
        List<BrokenRef> brokenRefs = health.brokenRefs() == null ? List.of() : health.brokenRefs();
        for (BrokenRef b : brokenRefs) {
            Map<String, Object> br = new LinkedHashMap<>();
            br.put("assetId", b.assetId());
            br.put("expectedPath", b.expectedPath());
            br.put("reason", b.reason());
            brokenRefsList.add(br);
        }
        manifest.put("brokenRefs", brokenRefsList);

        try {
            atomicWrite(target, MAPPER.writeValueAsString(manifest));
        } catch (Exception e) {
            throw new IllegalStateException("serialize manifest failed", e);
        }
    }

    private void writeMeta(Path runDir, AgentRun run, WorkspaceHealth health, String mode) {
        Path target = runDir.resolve("meta.json");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", "v0");
        meta.put("workspaceVersion", "v0");
        meta.put("mode", mode);
        meta.put("runId", run.getId());
        meta.put("userId", run.getUserId());
        meta.put("status", run.getStatus() == null ? null : run.getStatus().name());
        // D21-A 修复：裸 MAPPER 未注册 jackson-datatype-jsr310，直接放 OffsetDateTime
        // 会抛 InvalidDefinitionException（此前任何 startedAt 非空的真实 run 都会在此失败）；
        // 与本类其它文件一致，统一落 ISO-8601 字符串。
        meta.put("createdAt", run.getStartedAt() == null ? null : run.getStartedAt().toString());
        meta.put("startedAt", run.getStartedAt() == null ? null : run.getStartedAt().toString());
        meta.put("completedAt", run.getCompletedAt() == null ? null : run.getCompletedAt().toString());
        meta.put("lastError", run.getLastError());

        // conservative 降级（health == null）时不写 health 块：没有校验数据就不伪造。
        if (health != null) {
            Map<String, Object> healthBlock = new LinkedHashMap<>();
            healthBlock.put("verifiedAt", OffsetDateTime.now().toString());
            healthBlock.put("totalRefs", health.totalRefs());
            healthBlock.put("brokenRefs", health.brokenRefs().size());
            double ratio = health.totalRefs() == 0 ? 0.0 :
                    (double) health.brokenRefs().size() / health.totalRefs();
            healthBlock.put("brokenRatio", ratio);
            meta.put("health", healthBlock);
        }

        meta.put("ext", run.getExt());

        try {
            atomicWrite(target, MAPPER.writeValueAsString(meta));
        } catch (Exception e) {
            throw new IllegalStateException("serialize meta failed", e);
        }
    }

    private WorkspaceState computeWorkspaceState(AgentRun run, CollectedAssets assets,
                                                 WorkspaceHealth health, String mode) {
        OffsetDateTime completedAt = run.getCompletedAt();
        OffsetDateTime updatedAt = run.getUpdatedAt();
        // conservative 降级时 assets 可为 null → lastSeq = 0。
        List<AgentRunMessage> messages = assets == null || assets.messages() == null
                ? List.of() : assets.messages();
        int lastSeq = WorkspaceFingerprints.lastSeqOf(messages);
        String fingerprint = WorkspaceFingerprints.compute(completedAt, updatedAt, lastSeq);
        // 只有 full 模式做完整性声明；conservative 不落 brokenRefsCount（保持 null = 未知）。
        Integer brokenRefsCount = MODE_FULL.equals(mode) && health != null
                ? health.brokenRefs().size() : null;
        return new WorkspaceState(run.getId(), OffsetDateTime.now().toString(), "v0",
                completedAt == null ? "" : completedAt.toString(),
                updatedAt == null ? "" : updatedAt.toString(),
                lastSeq, fingerprint, mode, brokenRefsCount);
    }

    private void writeWorkspaceState(Path runDir, WorkspaceState state) {
        Path target = runDir.resolve(STATE_FILE_NAME);
        try {
            atomicWrite(target, MAPPER.writeValueAsString(state));
        } catch (Exception e) {
            throw new IllegalStateException("write workspace_state failed", e);
        }
    }

    private void atomicWrite(Path target, String content) {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalStateException("target 缺父目录: " + target);
        }
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 退化为非原子 move（容器挂载文件系统可能不支持）
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("atomic write failed: " + target, e);
        }
    }

    public record WriteResult(Path runDir, String fingerprint, int brokenCount) {}

    /**
     * workspace_state.json 结构（D21-A 起新增 mode/brokenRefsCount 两字段）。
     *
     * <p>兼容语义：旧文件缺 {@code mode} → 读回为 null → skip 判定视为 legacy，
     * 永不 skip、重 dump 一次后收敛到新格式；缺 {@code brokenRefsCount} → null = 未知，
     * 同样不 skip。ignoreUnknown 保留对未来字段的前向兼容。</p>
     *
     * @param mode             {@link #MODE_FULL}（五文件齐全）/ {@link #MODE_CONSERVATIVE}（减量）；legacy 文件为 null
     * @param brokenRefsCount  full 模式落盘时的 brokenRefs 数；conservative/legacy 为 null（不做完整性声明）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkspaceState(
            String lastRunId,
            String lastExtractedAt,
            String lastManifestVersion,
            String sourceRunCompletedAt,
            String sourceRunUpdatedAt,
            int lastMessageSeq,
            String fingerprint,
            String mode,
            Integer brokenRefsCount
    ) {}
}
