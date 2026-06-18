package world.willfrog.agent.service.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户 / run 目录解析 + username sanitize + 路径防护。
 *
 * <p>防护策略：字符串层先拒 ../、绝对路径前缀，再走
 * Path.resolve + toRealPath(NOFOLLOW_LINKS) 二次校验。
 *
 * <h3>依赖</h3>
 * <ul>
 *   <li>agent.workspace.root — workspace 根目录，默认 /data/agent_workspaces</li>
 *   <li>agent.tools.market-data.dataset.path — dataset 根目录，默认 /data/agent_datasets</li>
 * </ul>
 *
 * @author wang
 */
@Component
public class WorkspacePathResolver {

    private static final Pattern STRIP_CHARS = Pattern.compile("[/\\\\:*?\"<>|]");
    /**
     * 非安全字符集：仅允许 [a-z0-9._] 留下；其他（含 -、空格、中文）都替换为 _。
     * 这里故意不放 -：用户输入里的中划线可被 PathResolver 当作路径分隔风险，留给 _ 更安全。
     */
    private static final Pattern NON_SAFE_CHARS = Pattern.compile("[^a-z0-9._]");
    /**
     * 是否有 [a-z0-9]（字母/数字）字符；用于 hash fallback 触发判断。
     * 不含 _：全下划线（比如纯中文输入 strip 后只剩 _）视为无安全字符，触发 fallback。
     */
    private static final Pattern HAS_ALNUM_CHAR = Pattern.compile(".*[a-z0-9].*");
    private static final Set<String> RESERVED = Set.of("admin", "root", "system", ".", "..");

    private final Path workspaceRoot;
    private final Path datasetPath;

    public WorkspacePathResolver(
            @Value("${agent.workspace.root:/data/agent_workspaces}") String workspaceRoot,
            @Value("${agent.tools.market-data.dataset.path:/data/agent_datasets}") String datasetPath) {
        this.workspaceRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        this.datasetPath = Paths.get(datasetPath).toAbsolutePath().normalize();
    }

    /**
     * sanitize username。
     *
     * <p>流程：NFKC → strip 危险字符 → 非安全字符转 _ → 截断到 32 → 若不含 [a-z0-9._-] 或命中保留名则走 hash fallback。
     *
     * @param raw 原始 username
     * @return 安全的目录名片段（不含路径分隔符）
     */
    public String sanitizeUsername(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("username 不能为空");
        }
        String s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC).toLowerCase();
        s = STRIP_CHARS.matcher(s).replaceAll("");
        s = NON_SAFE_CHARS.matcher(s).replaceAll("_");
        s = s.length() > 32 ? s.substring(0, 32) : s;
        if (!HAS_ALNUM_CHAR.matcher(s).matches() || RESERVED.contains(s)) {
            String hash = Base64.getEncoder().withoutPadding().encodeToString(
                    sha256(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            s = "u" + hash.substring(0, 15).toLowerCase();
        }
        return s;
    }

    /**
     * 解析 user 目录 {userId}_{sanitizedUsername}，路径写入前双重校验。
     *
     * @param userId   必须正整数
     * @param username 原始 username（内部 sanitize）
     * @return user 目录绝对路径
     */
    public Path resolveUserDir(long userId, String username) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 必须正整数");
        }
        String sanitized = sanitizeUsername(username);
        Path target = workspaceRoot.resolve(userId + "_" + sanitized).normalize();
        validateInsideRoot(target, "user dir");
        return realpathNoFollow(target);
    }

    /**
     * 解析 run 目录 {workspaceRoot}/{userId}_{sanitized}/runs/{runId}/。
     */
    public Path resolveRunDir(String runId, long userId, String username) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        Path target = resolveUserDir(userId, username).resolve("runs").resolve(runId).normalize();
        validateInsideRoot(target, "run dir");
        return realpathNoFollow(target);
    }

    /**
     * 校验 manifest 内的 relativePath 不穿越 workspaceRoot。
     *
     * <p>先字符串层拒 ../、绝对路径前缀，再 resolve + startsWith 二次校验。
     */
    public Path validateRelativePath(Path base, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath 不能为空");
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")
                || relativePath.contains("..")) {
            throw new SecurityException("relativePath 非法: " + relativePath);
        }
        Path target = base.resolve(relativePath).normalize();
        validateInsideRoot(target, "relative path");
        return realpathNoFollow(target);
    }

    /**
     * 校验 dataset reference 路径必须 resolve 到 {dataset.path}/{dataset_id}/ 下。
     */
    public Path resolveDatasetRef(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("datasetId 不能为空");
        }
        Path target = datasetPath.resolve(datasetId).normalize();
        if (!target.startsWith(datasetPath)) {
            throw new SecurityException("datasetId 解析越界: " + datasetId);
        }
        return target;
    }

    /**
     * admin API 下载路径解析。上层 WorkspaceReadService 已调 runMapper.load + authorizeRunAccess，
     * 这里只做路径解析。
     */
    public Path resolveAndAuthorize(String runId, String relativePath) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        Path runDir = runBaseDir(runId);
        return validateRelativePath(runDir, relativePath);
    }

    /**
     * run 基础目录（仅供 admin API 下载用，与 resolveRunDir 走不同索引避免暴露用户名）。
     *
     * <h3>v0 布局偏差（已知 / 评审接受）</h3>
     * <p>计划文档（user-workspace-memory-v0-final.md）原始布局是
     * {@code {userId}_{username}/runs/{runId}/}。v0 落地改为
     * {@code _by_run_id_index/{runId}/} 走 workspaceRoot 根目录下，理由：
     * <ol>
     *   <li>agentService 落 dump 时 {@code AgentRun} 实体暂无 username 字段，v0 不查外部 user service；</li>
     *   <li>admin API 只读、不修改，按 runId 寻址比按 userId 寻址更直接；</li>
     *   <li>v0.1 计划补 {@code resolveRunDir(runId, userId, username)} 同时写到 {@code runs/{runId}/}，
     *       _by_run_id_index 仅作 admin fallback 索引。</li>
     * </ol>
     * 该偏差 Tracy 二轮 code review 接受，详见 task #14 thread msg a79b46d3 S1。
     */
    public Path runBaseDir(String runId) {
        Path target = workspaceRoot.resolve("_by_run_id_index").resolve(runId).normalize();
        validateInsideRoot(target, "run base dir");
        return realpathNoFollow(target);
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public Path getDatasetPath() {
        return datasetPath;
    }

    private void validateInsideRoot(Path target, String label) {
        if (!target.startsWith(workspaceRoot) && !target.startsWith(datasetPath)) {
            throw new SecurityException(label + " 越出 workspaceRoot/datasetPath: " + target);
        }
    }

    private Path realpathNoFollow(Path target) {
        try {
            // NOFOLLOW_LINKS：检查链接自身不穿越，不跟随内容
            return target.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            // 目录还不存在（首次 dump），返回 normalized 路径即可
            return target;
        } catch (IOException e) {
            throw new SecurityException("路径解析失败: " + target, e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
