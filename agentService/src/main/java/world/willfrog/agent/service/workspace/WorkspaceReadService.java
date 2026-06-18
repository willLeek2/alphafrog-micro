package world.willfrog.agent.service.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * workspace 只读查询服务。
 *
 * <p>鉴权模式：runMapper.findById → callerUserId == run.userId || isAdmin。
 * 文件读取统一走 {@link WorkspacePathResolver#resolveAndAuthorize} 返回的 Path，
 * service 不再额外接收 userId 参数。
 *
 * @author wang
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceReadService {

    private final AgentRunMapper runMapper;
    private final WorkspacePathResolver pathResolver;

    /**
     * 统一鉴权：run 主人或 admin 才能继续访问。
     *
     * @param runId        run 主键
     * @param callerUserId 调用方 userId
     * @param isAdmin      调用方是否为 admin
     * @throws IllegalArgumentException runId / callerUserId 为空
     * @throws IllegalStateException    run 不存在
     * @throws SecurityException        无权访问
     */
    public void authorizeRunAccess(String runId, String callerUserId, boolean isAdmin) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        if (callerUserId == null || callerUserId.isBlank()) {
            throw new SecurityException("callerUserId 不能为空");
        }
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            throw new IllegalStateException("run 不存在: " + runId);
        }
        if (!isAdmin && !callerUserId.equals(run.getUserId())) {
            throw new SecurityException("无权访问 run: " + runId);
        }
    }

    /**
     * 读取 meta.json。
     *
     * @param runId        run 主键
     * @param callerUserId 调用方 userId
     * @param isAdmin      调用方是否为 admin
     * @return 文件内容；文件不存在返回 Optional.empty()
     */
    public Optional<String> getMetaJson(String runId, String callerUserId, boolean isAdmin) {
        authorizeRunAccess(runId, callerUserId, isAdmin);
        Path file = pathResolver.resolveAndAuthorize(runId, "meta.json");
        return readIfExists(file);
    }

    /**
     * 读取 manifest.json。
     *
     * @param runId        run 主键
     * @param callerUserId 调用方 userId
     * @param isAdmin      调用方是否为 admin
     * @return 文件内容；文件不存在返回 Optional.empty()
     */
    public Optional<String> getManifestJson(String runId, String callerUserId, boolean isAdmin) {
        authorizeRunAccess(runId, callerUserId, isAdmin);
        Path file = pathResolver.resolveAndAuthorize(runId, "manifest.json");
        return readIfExists(file);
    }

    /**
     * 读取 conversation.jsonl。
     *
     * @param runId        run 主键
     * @param callerUserId 调用方 userId
     * @param isAdmin      调用方是否为 admin
     * @return 文件内容；文件不存在返回 Optional.empty()
     */
    public Optional<String> getConversationJsonl(String runId, String callerUserId, boolean isAdmin) {
        authorizeRunAccess(runId, callerUserId, isAdmin);
        Path file = pathResolver.resolveAndAuthorize(runId, "conversation.jsonl");
        return readIfExists(file);
    }

    /**
     * 读取 python_scripts.jsonl。
     *
     * @param runId        run 主键
     * @param callerUserId 调用方 userId
     * @param isAdmin      调用方是否为 admin
     * @return 文件内容；文件不存在返回 Optional.empty()
     */
    public Optional<String> getPythonScriptsJsonl(String runId, String callerUserId, boolean isAdmin) {
        authorizeRunAccess(runId, callerUserId, isAdmin);
        Path file = pathResolver.resolveAndAuthorize(runId, "python_scripts.jsonl");
        return readIfExists(file);
    }

    /**
     * 列指定用户的最近 run（仅元信息，不读文件）。
     *
     * <p>workspace 是否已生成由 read 端按需再判断。
     *
     * @param userId 用户 ID
     * @param limit  返回条数上限
     * @return run 列表
     */
    public List<AgentRun> listUserRuns(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return runMapper.listByUser(userId, null, null, limit, 0);
    }

    private Optional<String> readIfExists(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("read file failed: " + file, e);
        }
    }
}
