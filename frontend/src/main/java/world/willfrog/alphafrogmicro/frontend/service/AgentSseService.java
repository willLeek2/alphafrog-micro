package world.willfrog.alphafrogmicro.frontend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunEventResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunStatusResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSE 实时事件流服务 —— 管理 SSE 连接、snapshot 发送、Redis pub/sub 订阅、heartbeat 保活。
 *
 * <p>每个 agent run 的 SSE 连接生命周期：
 * <ol>
 *   <li>接收新连接 → 向 Redis channel {@code agent:events:{runId}} 注册 listener</li>
 *   <li>查询 run 状态和最近 {@code SNAPSHOT_EVENT_COUNT} 条历史事件 → 发送 snapshot</li>
 *   <li>进入 live 模式：Redis 收到新事件 → 通过 emitter 推送给客户端</li>
 *   <li>heartbeat 每 30s 一次，防止代理断连</li>
 *   <li>emitter onCompletion / onError / onTimeout → 清理 Redis listener 和 heartbeat 定时器</li>
 * </ol>
 *
 * <p>线程安全：{@link ConcurrentHashMap} 管理 emitter 和 heartbeat 任务的并发访问。
 * 同一个 runId 可以有多个 SSE 连接（多标签页），每个独立管理。</p>
 */
@Service
@Slf4j
public class AgentSseService {

    private static final int SNAPSHOT_EVENT_COUNT = 10;
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final String REDIS_CHANNEL_PREFIX = "agent:events:";

    /**
     * Dubbo 代理（langchain provider），通过字段注入，
     * 与 AgentController 中 agentDubboServiceLangchain 的注入方式一致。
     */
    @DubboReference(group = "langchain", check = false)
    private AgentDubboService agentDubboService;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisMessageListenerContainer redisListenerContainer;
    private final ObjectMapper objectMapper;

    public AgentSseService(StringRedisTemplate stringRedisTemplate,
                           RedisMessageListenerContainer redisListenerContainer,
                           ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisListenerContainer = redisListenerContainer;
        this.objectMapper = objectMapper;
    }

    /** 保存每个 emitter 关联的 heartbeat 定时任务，清理时 cancel */
    private final ConcurrentHashMap<SseEmitter, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    /** 保存每个 emitter 关联的 Redis listener，清理时 unsubscribe */
    private final ConcurrentHashMap<SseEmitter, MessageListener> listeners = new ConcurrentHashMap<>();

    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /**
     * 建立 SSE 连接，发送 snapshot 后进入 live 模式。
     *
     * @param runId   目标 agent run 的 ID
     * @param userId  已鉴权的用户 ID（用于权限校验）
     * @param emitter 新的 SseEmitter
     */
    public void connect(String runId, String userId, SseEmitter emitter) {
        String channelName = REDIS_CHANNEL_PREFIX + runId;

        // 1. 注册 Redis pub/sub listener（先订阅，减少 gap）
        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                emitter.send(SseEmitter.event()
                        .name("agent.event")
                        .data(body));
            } catch (IOException e) {
                log.debug("SSE send failed for runId={}, likely client disconnected", runId);
            }
        };
        redisListenerContainer.addMessageListener(listener, new ChannelTopic(channelName));
        listeners.put(emitter, listener);

        // 2. 发送 snapshot
        try {
            Map<String, Object> snapshot = buildSnapshot(runId, userId);
            emitter.send(SseEmitter.event().name("snapshot").data(objectMapper.writeValueAsString(snapshot)));
        } catch (Exception e) {
            log.warn("SSE snapshot failed for runId={}, sending error and closing", runId, e);
            sendErrorAndClose(emitter, "SNAPSHOT_FAILED", "无法获取 run 状态");
            cleanup(emitter, channelName);
            return;
        }

        // 3. 启动 heartbeat
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            try {
                Map<String, Object> hb = new LinkedHashMap<>();
                hb.put("type", "heartbeat");
                hb.put("ts", System.currentTimeMillis());
                emitter.send(SseEmitter.event().name("heartbeat").data(objectMapper.writeValueAsString(hb)));
            } catch (IOException e) {
                log.debug("SSE heartbeat failed for runId={}, likely client disconnected", runId);
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        heartbeatTasks.put(emitter, heartbeat);

        // 4. 注册清理回调
        emitter.onCompletion(() -> {
            log.debug("SSE completed for runId={}", runId);
            cleanup(emitter, channelName);
        });
        emitter.onError(ex -> {
            log.debug("SSE error for runId={}: {}", runId, ex.getMessage());
            cleanup(emitter, channelName);
        });
        emitter.onTimeout(() -> {
            log.debug("SSE timeout for runId={}", runId);
            cleanup(emitter, channelName);
        });

        log.info("SSE connected for runId={}", runId);
    }

    /**
     * 构建 snapshot：status、lastSeq、plan、最近 N 条历史 event。
     */
    private Map<String, Object> buildSnapshot(String runId, String userId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", "snapshot");

        // 查询 run 状态
        try {
            AgentRunStatusMessage statusMsg = agentDubboService.getStatus(
                    GetAgentRunStatusRequest.newBuilder().setUserId(userId).setId(runId).build());
            snapshot.put("status", emptyToNull(statusMsg.getStatus()));
            snapshot.put("phase", emptyToNull(statusMsg.getPhase()));
            snapshot.put("startedAtMs", statusMsg.getStartedAtMs() > 0 ? statusMsg.getStartedAtMs() : null);
            snapshot.put("completedAtMs", statusMsg.getCompletedAtMs() > 0 ? statusMsg.getCompletedAtMs() : null);

            // plan 摘要（todos 列表）
            String planJson = statusMsg.getPlanJson();
            if (planJson != null && !planJson.isBlank()) {
                snapshot.put("plan", parseJsonOrNull(planJson));
            }
        } catch (Exception e) {
            log.warn("SSE getStatus failed for runId={}: {}", runId, e.getMessage());
        }

        // 查询最近 N 条历史事件
        List<AgentRunEventResponse> recentEvents = new ArrayList<>();
        try {
            ListAgentRunEventsResponse eventsResp = agentDubboService.listEvents(
                    ListAgentRunEventsRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setLimit(Math.max(1, SNAPSHOT_EVENT_COUNT))
                            .setLatest(true)
                            .build());
            for (var e : eventsResp.getItemsList()) {
                recentEvents.add(new AgentRunEventResponse(
                        e.getId(), e.getRunId(), e.getSeq(), e.getEventType(),
                        parseJsonOrNull(e.getPayloadJson()), e.getCreatedAt()));
            }
        } catch (Exception e) {
            log.warn("SSE listEvents failed for runId={}: {}", runId, e.getMessage());
        }
        snapshot.put("events", recentEvents);
        snapshot.put("eventCount", recentEvents.size());

        // lastSeq 在 events 非空时设为最后一条的 seq
        if (!recentEvents.isEmpty()) {
            snapshot.put("lastSeq", recentEvents.get(recentEvents.size() - 1).seq());
        } else {
            snapshot.put("lastSeq", 0);
        }

        return snapshot;
    }

    /**
     * 发送 error 事件并关闭连接。
     */
    private void sendErrorAndClose(SseEmitter emitter, String code, String message) {
        try {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("type", "error");
            error.put("code", code);
            error.put("message", message);
            emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(error)));
        } catch (Exception ignored) {
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    /**
     * 应用关闭时优雅终止 heartbeat 线程池。
     */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /**
     * 清理 emitter 关联的资源：取消 heartbeat、移除 Redis listener。
     */
    private void cleanup(SseEmitter emitter, String channelName) {
        ScheduledFuture<?> heartbeat = heartbeatTasks.remove(emitter);
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        MessageListener listener = listeners.remove(emitter);
        if (listener != null) {
            try {
                redisListenerContainer.removeMessageListener(listener, new ChannelTopic(channelName));
            } catch (Exception e) {
                log.debug("Error removing Redis listener for channel {}: {}", channelName, e.getMessage());
            }
        }
    }

    private Object parseJsonOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
