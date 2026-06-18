package world.willfrog.agent.service.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import world.willfrog.agent.platform.entity.AgentRun;

import java.util.List;
import java.util.Map;

/**
 * workspace admin API。
 *
 * <p>鉴权：依赖上游 filter（dubbo / http）传入 callerUserId + isAdmin，
 * 本 controller 不重复校验 user 来源合法性。
 * run 级 access 校验由 {@link WorkspaceReadService#authorizeRunAccess} 统一处理。
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>GET /admin/workspace/users/{userId}/runs</li>
 *   <li>GET /admin/workspace/runs/{runId}/meta</li>
 *   <li>GET /admin/workspace/runs/{runId}/manifest</li>
 *   <li>GET /admin/workspace/runs/{runId}/conversation</li>
 *   <li>GET /admin/workspace/runs/{runId}/python-scripts</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/workspace")
@RequiredArgsConstructor
public class WorkspaceAdminController {

    private final WorkspaceReadService readService;

    /**
     * 列出指定用户的最近 run。
     *
     * <p>鉴权：{@code callerUserId == userId}（用户查自己） 或 {@code isAdmin=true}（admin 查任何人）。
     * 鉴权失败抛 403，由 controller 直接拒绝。</p>
     *
     * @param userId       目标用户 ID
     * @param callerUserId 调用方用户 ID
     * @param isAdmin      调用方是否为 admin
     * @param limit        返回条数上限（默认 50）
     * @return run 列表
     */
    @GetMapping("/users/{userId}/runs")
    public List<AgentRun> listUserRuns(
            @PathVariable String userId,
            @RequestParam String callerUserId,
            @RequestParam(defaultValue = "false") boolean isAdmin,
            @RequestParam(defaultValue = "50") int limit) {
        if (callerUserId == null || callerUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "callerUserId 必填");
        }
        if (!isAdmin && !callerUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "无权访问其他用户的 run 列表");
        }
        return readService.listUserRuns(userId, limit);
    }

    /**
     * 读取 run 的 meta.json。
     */
    @GetMapping("/runs/{runId}/meta")
    public Map<String, Object> getMeta(
            @PathVariable String runId,
            @RequestParam String callerUserId,
            @RequestParam(defaultValue = "false") boolean isAdmin) {
        return readService.getMetaJson(runId, callerUserId, isAdmin)
                .map(content -> Map.<String, Object>of("runId", runId, "content", content))
                .orElse(Map.of("runId", runId, "content", (Object) null));
    }

    /**
     * 读取 run 的 manifest.json。
     */
    @GetMapping("/runs/{runId}/manifest")
    public Map<String, Object> getManifest(
            @PathVariable String runId,
            @RequestParam String callerUserId,
            @RequestParam(defaultValue = "false") boolean isAdmin) {
        return readService.getManifestJson(runId, callerUserId, isAdmin)
                .map(content -> Map.<String, Object>of("runId", runId, "content", content))
                .orElse(Map.of("runId", runId, "content", (Object) null));
    }

    /**
     * 读取 run 的 conversation.jsonl。
     */
    @GetMapping("/runs/{runId}/conversation")
    public Map<String, Object> getConversation(
            @PathVariable String runId,
            @RequestParam String callerUserId,
            @RequestParam(defaultValue = "false") boolean isAdmin) {
        return readService.getConversationJsonl(runId, callerUserId, isAdmin)
                .map(content -> Map.<String, Object>of("runId", runId, "content", content))
                .orElse(Map.of("runId", runId, "content", (Object) null));
    }

    /**
     * 读取 run 的 python_scripts.jsonl。
     */
    @GetMapping("/runs/{runId}/python-scripts")
    public Map<String, Object> getPythonScripts(
            @PathVariable String runId,
            @RequestParam String callerUserId,
            @RequestParam(defaultValue = "false") boolean isAdmin) {
        return readService.getPythonScriptsJsonl(runId, callerUserId, isAdmin)
                .map(content -> Map.<String, Object>of("runId", runId, "content", content))
                .orElse(Map.of("runId", runId, "content", (Object) null));
    }
}
