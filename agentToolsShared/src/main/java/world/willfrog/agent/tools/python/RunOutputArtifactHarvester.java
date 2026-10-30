package world.willfrog.agent.tools.python;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.artifact.PersistentArtifactRegistry;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.storage.AgentStoragePaths;
import world.willfrog.alphafrogmicro.sandbox.idl.CollectedArtifactFile;
import world.willfrog.alphafrogmicro.sandbox.idl.SkippedArtifactFile;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;

/**
 * 014-new-03：把沙箱收割回传的交付文件落盘并幂等登记为 run_output 运行产物。
 *
 * <p>三个终态出口共用这一个实现：生产同步路径（terminalSideEffect 回调）、等待组
 * 接回路径（TerminalFormatContextFactory）、挂起恢复路径（ToolJobFinalizer，与
 * finance 同位处理）。任何一步失败只 warn 跳过该文件，不影响任务结果——与惰性
 * 注册（AgentArtifactService）的降级语义一致。</p>
 */
@Component
public class RunOutputArtifactHarvester {

    private static final Logger log = LoggerFactory.getLogger(RunOutputArtifactHarvester.class);

    /** 014-new-03：收割产物的登记类型。不进 EVENT_DERIVED_TYPES，保留登记表自管的 TTL/清理语义。 */
    public static final String RUN_OUTPUT_ARTIFACT_TYPE = "run_output";

    /** 配置 <=0（永不过期）时的 TTL 上界，对齐惰性注册先例的取值。 */
    private static final long UNLIMITED_TTL_HOURS = 24L * 365L;

    @Autowired(required = false)
    private PersistentArtifactRegistry persistentArtifactRegistry;

    @Autowired(required = false)
    private AgentStoragePaths agentStoragePaths;

    @Autowired(required = false)
    private AgentRunMapper agentRunMapper;

    /** 收割登记 TTL：normal/admin 两档 retention 取长档 ×24 小时（配置键与惰性注册先例一致）。 */
    @Value("${agent.artifact.retention-days.normal:7}")
    private int artifactNormalRetentionDays;

    @Value("${agent.artifact.retention-days.admin:30}")
    private int artifactAdminRetentionDays;

    /**
     * 收割入口。响应里没有收割字段（开关未开、失败路径、旧版本沙箱）时零动作。
     *
     * @param runId   产物归属的 run
     * @param userId 调用方已知的归属用户；为空时查 Run 记录补齐
     * @param result  沙箱终态结果响应
     */
    public void harvest(String runId, String userId, TaskResultResponse result) {
        if (result == null || result.getCollectedArtifactsCount() == 0) {
            return;
        }
        for (SkippedArtifactFile skipped : result.getSkippedArtifactsList()) {
            log.info("沙箱收割跳过一个产物文件: runId={} taskId={} filename={} reason={}",
                    runId, result.getTaskId(), skipped.getFilename(), skipped.getReason());
        }
        if (persistentArtifactRegistry == null || agentStoragePaths == null) {
            log.warn("收割产物登记接线缺失，本次产物不落盘: runId={} taskId={} registryReady={} storagePathsReady={}",
                    runId, result.getTaskId(), persistentArtifactRegistry != null, agentStoragePaths != null);
            return;
        }
        String taskId = result.getTaskId();
        // runId / taskId 会拼进落盘路径，与文件名一样只允许纯 basename（防路径逃逸）。
        if (!isSafeArtifactFileName(runId) || !isSafeArtifactFileName(taskId)) {
            log.warn("收割产物的 runId/taskId 含路径成分，拒绝落盘: runId={} taskId={}", runId, taskId);
            return;
        }
        String owner = firstNonBlank(userId, userIdOfRun(runId));
        if (owner == null || owner.isBlank()) {
            log.warn("收割产物登记缺少归属用户，跳过: runId={} taskId={}", runId, taskId);
            return;
        }
        long ttlHours = runOutputTtlHours();
        // 落盘根必须位于 artifactRoot 内（登记表 external 路径校验只认这个根），
        // 目录按任务身份分层：同 run 不同任务的同名文件互不覆盖。
        Path taskDir = agentStoragePaths.artifactRoot().toAbsolutePath().normalize()
                .resolve(RUN_OUTPUT_ARTIFACT_TYPE).resolve(runId).resolve(taskId);
        for (CollectedArtifactFile collected : result.getCollectedArtifactsList()) {
            harvestOneFile(runId, owner, taskId, collected, taskDir, ttlHours);
        }
    }

    /** 单个收割文件：解压 → 校验大小与哈希 → 落盘 → 幂等登记；任一步失败只 warn 跳过该文件。 */
    private void harvestOneFile(String runId, String userId, String taskId,
                                CollectedArtifactFile collected, Path taskDir, long ttlHours) {
        String filename = collected.getFilename();
        if (!isSafeArtifactFileName(filename)) {
            log.warn("收割产物文件名不是纯文件名（拒绝路径分隔符），跳过: runId={} taskId={} filename={}",
                    runId, taskId, filename);
            return;
        }
        byte[] raw;
        try {
            raw = gunzip(collected.getGzipData().toByteArray());
        } catch (IOException decompressFailure) {
            log.warn("收割产物解压失败，跳过: runId={} taskId={} filename={} err={}",
                    runId, taskId, filename, decompressFailure.getMessage());
            return;
        }
        if (collected.getSizeBytes() > 0 && collected.getSizeBytes() != raw.length) {
            log.warn("收割产物大小与沙箱上报不一致，跳过: runId={} taskId={} filename={} reportedBytes={} actualBytes={}",
                    runId, taskId, filename, collected.getSizeBytes(), raw.length);
            return;
        }
        String actualSha256 = sha256Hex(raw);
        if (!collected.getSha256().isBlank()
                && !collected.getSha256().equalsIgnoreCase(actualSha256)) {
            log.warn("收割产物哈希与沙箱上报不一致，跳过: runId={} taskId={} filename={} reportedSha256={} actualSha256={}",
                    runId, taskId, filename, collected.getSha256(), actualSha256);
            return;
        }
        Path target;
        try {
            Files.createDirectories(taskDir);
            target = taskDir.resolve(filename).normalize();
            if (!target.startsWith(taskDir)) {
                log.warn("收割产物目标路径越出任务目录，跳过: runId={} taskId={} filename={}",
                        runId, taskId, filename);
                return;
            }
            Files.write(target, raw);
        } catch (IOException writeFailure) {
            log.warn("收割产物落盘失败，跳过登记: runId={} taskId={} filename={} err={}",
                    runId, taskId, filename, writeFailure.getMessage());
            return;
        }
        try {
            // 幂等键 = (runId, type, logicalId, path)：任务重试/重复收割返回同一 artifactId，零重写。
            persistentArtifactRegistry.registerExternalIdempotent(
                    runId, userId, RUN_OUTPUT_ARTIFACT_TYPE,
                    taskId + "/" + filename, filename, target, ttlHours);
            log.info("收割产物已登记: runId={} taskId={} filename={} path={} ttlHours={}",
                    runId, taskId, filename, target, ttlHours);
        } catch (Exception registrationFailure) {
            ModelSendBlocks.rethrowIfBlocked(registrationFailure);
            log.warn("收割产物登记失败（不影响任务结果）: runId={} taskId={} filename={} err={}",
                    runId, taskId, filename, registrationFailure.getMessage());
        }
    }

    /**
     * 收割登记的归属用户。前台路径优先用调用方传入值；查 Run 记录时先按部署身份查，
     * 部署身份不可用（挂起恢复等后台线程没有可信 ThreadLocal）时退回按主键查——
     * 对齐 ToolJobEventHookImpl 的后台直读先例。
     */
    private String userIdOfRun(String runId) {
        if (agentRunMapper == null) {
            return null;
        }
        try {
            AgentRun run = agentRunMapper.findById(runId);
            return run == null ? null : run.getUserId();
        } catch (Exception lookupFailure) {
            ModelSendBlocks.rethrowIfBlocked(lookupFailure);
            log.warn("查询 Run 归属用户失败: runId={} err={}", runId, lookupFailure.getMessage());
            return null;
        }
    }

    /**
     * 收割产物注册 TTL。按惰性注册先例（AgentArtifactService.registrationTtlHours）
     * 同式重算：取 normal/admin 两档 retention 的长档 ×24 小时；配置 <=0（永不过期）时取一年上界。
     */
    private long runOutputTtlHours() {
        long days = Math.max(artifactNormalRetentionDays, artifactAdminRetentionDays);
        if (days <= 0) {
            return UNLIMITED_TTL_HOURS;
        }
        return days * 24L;
    }

    /** 收割产物文件名只允许纯 basename：非空、无两种路径分隔符、不是本目录/上级目录引用。 */
    private static boolean isSafeArtifactFileName(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        return !filename.contains("/") && !filename.contains("\\")
                && !".".equals(filename) && !"..".equals(filename);
    }

    /** 解 gzip 得到原始字节；坏输入（非 gzip 流/截断）抛 IOException 由调用方降级。 */
    private static byte[] gunzip(byte[] gzipBytes) throws IOException {
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(gzipBytes);
             GZIPInputStream gzipIn = new GZIPInputStream(byteIn)) {
            return gzipIn.readAllBytes();
        }
    }

    /** 原始字节的 SHA-256 十六进制串（不带 sha256: 前缀，与 Python 侧 hexdigest 对齐）。 */
    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes == null ? new byte[0] : bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            ModelSendBlocks.rethrowIfBlocked(impossible);
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }
}
