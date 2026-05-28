package world.willfrog.alphafrogmicro.frontend.controller.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.AgentSseService;

/**
 * Agent run SSE 实时事件流 endpoint。
 *
 * <p>EventSource 连接 {@code /api/agent/runs/{runId}/stream}，
 * 鉴权通过 HttpOnly Cookie 或 Bearer header 自动完成。
 * 首包为 snapshot（run 状态 + 最近 N 条历史 event），之后逐条推送 live 事件。</p>
 *
 * <p>SSE event type：</p>
 * <ul>
 *   <li>{@code snapshot} — 首包，含 status / lastSeq / plan / 最近 N 条 event</li>
 *   <li>{@code agent.event} — live 事件，id 为 DB seq，payload 为 JSON object</li>
 *   <li>{@code run.status} — 状态/phase 变更</li>
 *   <li>{@code heartbeat} — 保活，每 30s</li>
 *   <li>{@code run.done} — run 终止</li>
 *   <li>{@code error} — 鉴权失败等异常，发完后 close 连接</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class AgentSseController {

    private final AgentSseService sseService;
    private final AuthService authService;

    @GetMapping(value = "/api/agent/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication,
                             @PathVariable("runId") String runId,
                             @RequestParam(value = "after_seq", required = false) Integer afterSeq,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            SseEmitter errorEmitter = new SseEmitter();
            try {
                errorEmitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"type\":\"error\",\"code\":\"UNAUTHORIZED\",\"message\":\"未登录或用户不存在\"}"));
                errorEmitter.complete();
            } catch (Exception ignored) {
            }
            return errorEmitter;
        }

        SseEmitter emitter = new SseEmitter(-1L);
        sseService.connect(runId, userId, resolveResumeAfterSeq(afterSeq, lastEventId), emitter);
        return emitter;
    }

    int resolveResumeAfterSeq(Integer afterSeq, String lastEventId) {
        if (afterSeq != null) {
            return Math.max(0, afterSeq);
        }
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(lastEventId.trim()));
        } catch (NumberFormatException e) {
            log.debug("Ignore invalid Last-Event-ID for agent SSE: {}", lastEventId);
            return 0;
        }
    }

    /**
     * 从 Spring Security Authentication 获取 userId，
     * 与 {@link AgentController#resolveUserId} 逻辑一致。
     */
    private String resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String username = authentication.getName();
        User user = authService.getUserByUsername(username);
        if (user == null || user.getUserId() == null) {
            return null;
        }
        return String.valueOf(user.getUserId());
    }
}
