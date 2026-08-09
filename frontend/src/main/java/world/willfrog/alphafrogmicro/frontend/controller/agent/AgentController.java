package world.willfrog.alphafrogmicro.frontend.controller.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunCostMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactPartMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactPartsMetaMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentSnapshotPartMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentSnapshotPartsMetaMessage;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DeleteAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentArtifactPartRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentArtifactPartsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCostRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.RefreshAgentRunCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentSnapshotPartRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentSnapshotPartsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsResponse;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunResponse;
import world.willfrog.alphafrogmicro.agent.idl.PauseAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsResponse;
import world.willfrog.alphafrogmicro.agent.idl.SubmitAgentFeedbackRequest;
import world.willfrog.alphafrogmicro.agent.idl.UpdateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageRequest;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesResponse;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunCreateRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentArtifactResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentArtifactPartsMetaResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentExportRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentExportResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentFeedbackRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunEventResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunEventsPageResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunResumeRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunCostResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunCreditsResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunResultResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunListItemResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunListResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentSnapshotPartsMetaResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunStatusResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunUpdateRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentMessageSendRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentMessageSendResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentMessageItemResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentMessageListResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.TraceListResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCallDetailResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.TraceDetailResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.TraceSpanItem;
import world.willfrog.alphafrogmicro.frontend.model.agent.TimelineResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentCallDetailBlobReader;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentCallDetailMapper;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentExternalObservabilityMapper;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentRawTraceDetailMapper;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentRunResultCacheService;

import java.math.BigDecimal;
import java.util.Optional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * Agent run HTTP API.
 * {@code /api/agent/**} routes exclusively to the {@code langchain} Dubbo group.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private static final String AGENT_RUNS = "/api/agent/runs";

    private static final int ADMIN_USER_TYPE = 1127;
    private static final int OBSERVABILITY_FULL_MAX_BYTES = 5 * 1024 * 1024;
    private static final int INLINE_FULL_MAX_BYTES = 256 * 1024;
    private static final int TRACE_FULL_DEFAULT_PART_SIZE = 512 * 1024;
    private static final int TRACE_FULL_MIN_PART_SIZE = 64 * 1024;
    private static final int TRACE_FULL_MAX_PART_SIZE = 2 * 1024 * 1024;

    @DubboReference(group = "langchain", check = false)
    private AgentDubboService agentDubboServiceLangchain;

    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final AgentRunResultCacheService runResultCacheService;
    private final AgentCallDetailBlobReader callDetailBlobReader;

    private AgentDubboService resolveService() {
        return agentDubboServiceLangchain;
    }

    @PostMapping(AGENT_RUNS)
    public ResponseWrapper<AgentRunResponse> create(Authentication authentication,
                                                    @RequestBody AgentRunCreateRequest request) {
        return createRun(authentication, request, agentDubboServiceLangchain);
    }

    private ResponseWrapper<AgentRunResponse> createRun(Authentication authentication,
                                                        AgentRunCreateRequest request,
                                                        AgentDubboService dubboService) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        User user = authService.getUserByUsername(authentication.getName());
        if (!authService.isUserActive(user)) {
            return ResponseWrapper.error(ResponseCode.FORBIDDEN, "账号已被禁用，无法创建新任务");
        }
        boolean admin = isAdmin(authentication);
        if (!admin && !hasPositiveCredit(user)) {
            return ResponseWrapper.error(ResponseCode.FORBIDDEN, "credit 余额不足，无法创建新任务");
        }
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseWrapper.paramError("message 不能为空");
        }
        try {
            Map<String, Object> contextMap = request.context() == null
                    ? new HashMap<>()
                    : new HashMap<>(request.context());
            boolean captureLlmRequests = Boolean.TRUE.equals(request.captureLlmRequests());
            boolean debugMode = Boolean.TRUE.equals(request.debugMode())
                    || toBoolean(contextMap.get("debugMode"))
                    || toBoolean(contextMap.get("debug_mode"));
            String provider = nvl(request.provider());
            String modelName = nvl(request.modelName());
            String endpointName = nvl(request.endpointName());
            Integer plannerCandidateCount = request.plannerCandidateCount();
            if (request.config() != null) {
                contextMap.put("config", request.config());
                ParsedModelSelection modelSelection = parseModelSelection(request.config().model());
                if (!modelSelection.modelName().isBlank()) {
                    modelName = modelSelection.modelName();
                }
                if (!modelSelection.endpointName().isBlank()) {
                    endpointName = modelSelection.endpointName();
                }
            }
            if (captureLlmRequests) {
                contextMap.put("captureLlmRequests", true);
            }
            if (debugMode) {
                contextMap.put("debugMode", true);
            }
            if (!provider.isBlank()) {
                contextMap.put("provider", provider);
            }
            int plannerCandidateCountForRpc = 0;
            if (plannerCandidateCount != null && plannerCandidateCount > 0) {
                if (admin) {
                    plannerCandidateCountForRpc = plannerCandidateCount;
                } else {
                    log.info("Ignore plannerCandidateCount for non-admin user: userId={}, value={}", userId, plannerCandidateCount);
                }
            }
            String contextJson = contextMap.isEmpty() ? "" : objectMapper.writeValueAsString(contextMap);
            String stageConfigJson = nvl(request.stageConfigJson());
            log.info("[AgentController] 创建 Run: userId={}, stageConfigJson={}", userId, stageConfigJson);
            AgentRunMessage run = dubboService.createRun(
                    CreateAgentRunRequest.newBuilder()
                            .setUserId(userId)
                            .setMessage(request.message())
                            .setContextJson(contextJson)
                            .setIdempotencyKey(nvl(request.idempotencyKey()))
                            .setModelName(modelName)
                            .setEndpointName(endpointName)
                            .setCaptureLlmRequests(captureLlmRequests)
                            .setProvider(provider)
                            .setPlannerCandidateCount(plannerCandidateCountForRpc)
                            .setDebugMode(debugMode)
                            .setStageConfigJson(stageConfigJson)
                            .build());
            return ResponseWrapper.success(toRunResponse(run, admin));
        } catch (RpcException e) {
            return handleRpcError(e, "创建 agent run");
        } catch (Exception e) {
            return handleError(e, "创建 agent run");
        }
    }

    @GetMapping(AGENT_RUNS)
    public ResponseWrapper<AgentRunListResponse> list(Authentication authentication,
                                                      @RequestParam(value = "limit", required = false) Integer limit,
                                                      @RequestParam(value = "offset", required = false) Integer offset,
                                                      @RequestParam(value = "page", required = false) Integer page,
                                                      @RequestParam(value = "size", required = false) Integer size,
                                                      @RequestParam(value = "max", required = false) Integer max,
                                                      @RequestParam(value = "status", required = false, defaultValue = "") String status,
                                                      @RequestParam(value = "days", required = false, defaultValue = "0") int days) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            int resolvedLimit = resolveLimit(limit, size, max);
            int resolvedOffset = resolveOffset(offset, page, resolvedLimit);
            ListAgentRunsResponse resp = resolveService().listRuns(
                    ListAgentRunsRequest.newBuilder()
                            .setUserId(userId)
                            .setLimit(resolvedLimit)
                            .setOffset(resolvedOffset)
                            .setStatus(nvl(status))
                            .setDays(days)
                            .build()
            );
            List<AgentRunListItemResponse> items = new ArrayList<>();
            for (var item : resp.getItemsList()) {
                items.add(new AgentRunListItemResponse(
                        item.getId(),
                        emptyToNull(item.getMessage()),
                        item.getStatus(),
                        emptyToNull(item.getCreatedAt()),
                        emptyToNull(item.getCompletedAt()),
                        item.getHasArtifacts(),
                        item.getDurationMs() <= 0 ? null : item.getDurationMs(),
                        item.getTotalTokens() <= 0 ? null : item.getTotalTokens(),
                        item.getToolCalls() <= 0 ? null : item.getToolCalls()
                ));
            }
            return ResponseWrapper.success(new AgentRunListResponse(items, resp.getTotal(), resp.getHasMore()));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 agent run 列表");
        } catch (Exception e) {
            return handleError(e, "查询 agent run 列表");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}")
    public ResponseWrapper<AgentRunResponse> get(Authentication authentication,
                                                @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentRunMessage run = resolveService().getRun(GetAgentRunRequest.newBuilder().setUserId(userId).setId(runId).build());
            return ResponseWrapper.success(toRunResponse(run, isAdmin(authentication)));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 agent run");
        } catch (Exception e) {
            return handleError(e, "查询 agent run");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/snapshot/parts")
    public ResponseWrapper<AgentSnapshotPartsMetaResponse> snapshotParts(Authentication authentication,
                                                                        @PathVariable("runId") String runId,
                                                                        @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        if (!isAdmin(authentication)) {
            return ResponseWrapper.error(ResponseCode.FORBIDDEN, "完整 snapshot 仅管理员可访问");
        }
        try {
            AgentSnapshotPartsMetaMessage meta = resolveService().getSnapshotPartsMeta(
                    GetAgentSnapshotPartsRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setMaxPartSize(maxPartSize)
                            .build()
            );
            return ResponseWrapper.success(toSnapshotPartsMetaResponse(meta));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 agent snapshot parts");
        } catch (Exception e) {
            return handleError(e, "查询 agent snapshot parts");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/snapshot/parts/{partIndex}")
    public ResponseEntity<byte[]> snapshotPart(Authentication authentication,
                                               @PathVariable("runId") String runId,
                                               @PathVariable("partIndex") int partIndex,
                                               @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return snapshotPartError(401, "UNAUTHORIZED");
        }
        if (!isAdmin(authentication)) {
            return snapshotPartError(403, "FORBIDDEN");
        }
        try {
            AgentSnapshotPartMessage part = resolveService().getSnapshotPart(
                    GetAgentSnapshotPartRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setPartIndex(partIndex)
                            .setMaxPartSize(maxPartSize)
                            .build()
            );
            byte[] content = part.getContent().toByteArray();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(content.length);
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
            headers.set("X-Snapshot-Compression", nvl(part.getCompression()));
            headers.set("X-Snapshot-Part-Index", String.valueOf(part.getPartIndex()));
            headers.set("X-Snapshot-Part-Size", String.valueOf(part.getPartSize()));
            headers.set("X-Snapshot-Total-Parts", String.valueOf(part.getTotalParts()));
            return ResponseEntity.ok().headers(headers).body(content);
        } catch (RpcException e) {
            log.error("查询 agent snapshot part 失败: {}", e.getMessage());
            return snapshotPartError(resolveSnapshotPartErrorStatus(e.getMessage()), "RPC_ERROR");
        } catch (Exception e) {
            log.error("查询 agent snapshot part 失败", e);
            return snapshotPartError(resolveSnapshotPartErrorStatus(e.getMessage()), "ERROR");
        }
    }

    @PutMapping(AGENT_RUNS + "/{runId}")
    public ResponseWrapper<AgentRunResponse> update(Authentication authentication,
                                                    @PathVariable("runId") String runId,
                                                    @RequestBody(required = false) AgentRunUpdateRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        if (request == null || request.title() == null || request.title().isBlank()) {
            return ResponseWrapper.paramError("title 不能为空");
        }
        String title = request.title().trim();
        if (title.length() > 120) {
            return ResponseWrapper.paramError("title 长度不能超过 120");
        }
        try {
            AgentRunMessage run = resolveService().updateRun(
                    UpdateAgentRunRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setTitle(title)
                            .build()
            );
            return ResponseWrapper.success(toRunResponse(run, isAdmin(authentication)));
        } catch (RpcException e) {
            return handleRpcError(e, "更新 agent run");
        } catch (Exception e) {
            return handleError(e, "更新 agent run");
        }
    }

    /**
     * 删除指定的 Agent Run（运行中的任务禁止删除）。
     * <p>
     * 删除行为说明：
     * <ul>
     *   <li>只能删除属于自己的 run（通过当前登录用户鉴权）</li>
     *   <li>运行中的 run（状态为 RECEIVED/PLANNING/EXECUTING/SUMMARIZING）禁止删除，需先取消或暂停</li>
     *   <li>删除后会同步清理 Redis 中的状态缓存</li>
     * </ul>
     * <p>
     * 异常响应：
     * <ul>
     *   <li>401：用户未登录</li>
     *   <li>404：run 不存在</li>
     *   <li>409：run 正在运行中，需先取消/暂停</li>
     * </ul>
     *
     * @param authentication 当前用户认证信息
     * @param runId          要删除的 run ID（路径参数）
     * @return 删除成功返回 "ok"，失败返回对应错误码
     */
    @DeleteMapping(AGENT_RUNS + "/{runId}")
    public ResponseWrapper<String> delete(Authentication authentication,
                                          @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            resolveService().deleteRun(DeleteAgentRunRequest.newBuilder().setUserId(userId).setId(runId).build());
            return ResponseWrapper.success("ok");
        } catch (RpcException e) {
            return handleRpcError(e, "删除 agent run");
        } catch (Exception e) {
            return handleError(e, "删除 agent run");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/events")
    public ResponseWrapper<AgentRunEventsPageResponse> events(Authentication authentication,
                                                             @PathVariable("runId") String runId,
                                                             @RequestParam(value = "after_seq", required = false, defaultValue = "0") int afterSeq,
                                                             @RequestParam(value = "limit", required = false, defaultValue = "200") int limit) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            ListAgentRunEventsResponse resp = resolveService().listEvents(
                    ListAgentRunEventsRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setAfterSeq(Math.max(0, afterSeq))
                            .setLimit(Math.min(Math.max(1, limit), 500))
                            .build()
            );
            List<AgentRunEventResponse> items = new ArrayList<>();
            for (var e : resp.getItemsList()) {
                items.add(new AgentRunEventResponse(
                        e.getId(),
                        e.getRunId(),
                        e.getSeq(),
                        e.getEventType(),
                        parseOutboundJson(e.getPayloadJson(), AgentExternalObservabilityMapper.View.EVENT),
                        e.getCreatedAt()
                ));
            }
            return ResponseWrapper.success(new AgentRunEventsPageResponse(items, resp.getNextAfterSeq(), resp.getHasMore()));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 agent events");
        } catch (Exception e) {
            return handleError(e, "查询 agent events");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/timeline")
    public ResponseWrapper<TimelineResponse> timeline(Authentication authentication,
                                                      @PathVariable("runId") String runId,
                                                      @RequestParam(value = "after_seq", required = false, defaultValue = "0") int afterSeq,
                                                      @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            int safeLimit = Math.min(Math.max(1, limit), 500);
            ListAgentRunEventsResponse resp = resolveService().listEvents(
                    ListAgentRunEventsRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setAfterSeq(Math.max(0, afterSeq))
                            .setLimit(safeLimit)
                            .build()
            );
            List<TimelineResponse.TimelineItem> items = new ArrayList<>();
            String minEventTime = null;
            String maxEventTime = null;
            for (var e : resp.getItemsList()) {
                Object payload = parseOutboundJson(e.getPayloadJson(), AgentExternalObservabilityMapper.View.EVENT);
                String eventTime = safeTimelineString(e.getCreatedAt(), 128);
                String eventType = safeTimelineString(e.getEventType(), 200);
                if (minEventTime == null || eventTime.compareTo(minEventTime) < 0) {
                    minEventTime = eventTime;
                }
                if (maxEventTime == null || eventTime.compareTo(maxEventTime) > 0) {
                    maxEventTime = eventTime;
                }
                items.add(new TimelineResponse.TimelineItem(
                        e.getSeq(),
                        "event",
                        null,
                        eventType,
                        eventTime,
                        safeTimelineString(timelineTitle(eventType, payload), 120),
                        null,
                        payload
                ));
            }
            appendTraceTimelineItems(userId, runId, isAdmin(authentication), items, minEventTime, maxEventTime,
                    Math.max(0, safeLimit - items.size()));
            items.sort(Comparator
                    .comparing(TimelineResponse.TimelineItem::time, Comparator.nullsLast(String::compareTo))
                    .thenComparing(TimelineResponse.TimelineItem::source, Comparator.nullsLast(String::compareTo))
                    .thenComparingInt(TimelineResponse.TimelineItem::seq));
            return ResponseWrapper.success(new TimelineResponse(items, resp.getNextAfterSeq(), resp.getHasMore()));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 timeline");
        } catch (Exception e) {
            return handleError(e, "查询 timeline");
        }
    }

    @PostMapping(AGENT_RUNS + "/{runId}:cancel")
    public ResponseWrapper<AgentRunResponse> cancel(Authentication authentication,
                                                   @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentRunMessage run = resolveService().cancelRun(CancelAgentRunRequest.newBuilder().setUserId(userId).setId(runId).build());
            return ResponseWrapper.success(toRunResponse(run, isAdmin(authentication)));
        } catch (RpcException e) {
            return handleRpcError(e, "取消 agent run");
        } catch (Exception e) {
            return handleError(e, "取消 agent run");
        }
    }

    @PostMapping(AGENT_RUNS + "/{runId}:pause")
    public ResponseWrapper<AgentRunResponse> pause(Authentication authentication,
                                                  @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentRunMessage run = resolveService().pauseRun(PauseAgentRunRequest.newBuilder().setUserId(userId).setId(runId).build());
            return ResponseWrapper.success(toRunResponse(run, isAdmin(authentication)));
        } catch (RpcException e) {
            return handleRpcError(e, "暂停 agent run");
        } catch (Exception e) {
            return handleError(e, "暂停 agent run");
        }
    }

    @PostMapping(AGENT_RUNS + "/{runId}:resume")
    public ResponseWrapper<AgentRunResponse> resume(Authentication authentication,
                                                   @PathVariable("runId") String runId,
                                                   @RequestBody(required = false) AgentRunResumeRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            String planOverrideJson = request == null ? "" : nvl(request.planOverrideJson());
            AgentRunMessage run = resolveService().resumeRun(ResumeAgentRunRequest.newBuilder()
                    .setUserId(userId)
                    .setId(runId)
                    .setPlanOverrideJson(planOverrideJson)
                    .build());
            return ResponseWrapper.success(toRunResponse(run, isAdmin(authentication)));
        } catch (RpcException e) {
            return handleRpcError(e, "续做 agent run");
        } catch (Exception e) {
            return handleError(e, "续做 agent run");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/result")
    public ResponseEntity<ResponseWrapper<AgentRunResultResponse>> result(Authentication authentication,
                                                                          @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).body(ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在"));
        }
        try {
            boolean admin = isAdmin(authentication);
            AgentRunResultMessage result = loadRunResult(userId, runId, admin);
            AgentRunResultResponse body = new AgentRunResultResponse(
                    result.getId(),
                    result.getStatus(),
                    emptyToNull(result.getAnswer()),
                    emptyToNull(result.getAnswerMarkdown()),
                    parseOutboundJson(result.getStructuredAnswerJson(), admin
                            ? AgentExternalObservabilityMapper.View.ADMIN
                            : AgentExternalObservabilityMapper.View.STRUCTURED),
                    parseOutboundJson(result.getPayloadJson(), admin
                            ? AgentExternalObservabilityMapper.View.ADMIN
                            : AgentExternalObservabilityMapper.View.RUN_SNAPSHOT),
                    null,
                    Math.max(0, result.getTotalCreditsConsumed())
            );
            if (!"COMPLETED".equalsIgnoreCase(result.getStatus())) {
                return ResponseEntity.status(202).body(ResponseWrapper.success(body, "任务未完成"));
            }
            return ResponseEntity.ok(ResponseWrapper.success(body));
        } catch (RpcException e) {
            return ResponseEntity.ok(handleRpcError(e, "获取 agent result"));
        } catch (Exception e) {
            return ResponseEntity.ok(handleError(e, "获取 agent result"));
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/cost")
    public ResponseWrapper<AgentRunCostResponse> cost(Authentication authentication,
                                                      @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentRunCostMessage cost = resolveService().getRunCost(
                    GetAgentRunCostRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .build()
            );
            return ResponseWrapper.success(toCostResponse(cost));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 agent run cost");
        } catch (Exception e) {
            return handleError(e, "查询 agent run cost");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/credits")
    public ResponseWrapper<AgentRunCreditsResponse> runCredits(Authentication authentication,
                                                               @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            GetAgentRunCreditsResponse resp = resolveService().getRunCredits(
                    GetAgentRunCreditsRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setIsAdmin(isAdmin(authentication))
                            .build()
            );
            return ResponseWrapper.success(toRunCreditsResponse(resp));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 agent run credits");
        } catch (Exception e) {
            return handleError(e, "查询 agent run credits");
        }
    }

    @PostMapping(AGENT_RUNS + "/{runId}/credits:refresh")
    public ResponseWrapper<AgentRunCreditsResponse> refreshRunCredits(Authentication authentication,
                                                                       @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            GetAgentRunCreditsResponse resp = agentDubboServiceLangchain.refreshRunCredits(
                    RefreshAgentRunCreditsRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setIsAdmin(isAdmin(authentication))
                            .build()
            );
            return ResponseWrapper.success(toRunCreditsResponse(resp));
        } catch (RpcException e) {
            return handleRpcError(e, "刷新 agent run credits");
        } catch (Exception e) {
            return handleError(e, "刷新 agent run credits");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/status")
    public ResponseWrapper<AgentRunStatusResponse> status(Authentication authentication,
                                                          @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentRunStatusMessage status = resolveService().getStatus(
                    GetAgentRunStatusRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .build()
            );
            boolean admin = isAdmin(authentication);
            return ResponseWrapper.success(new AgentRunStatusResponse(
                    status.getId(),
                    emptyToNull(status.getStatus()),
                    emptyToNull(status.getPhase()),
                    emptyToNull(status.getCurrentTool()),
                    emptyToNull(status.getLastEventType()),
                    emptyToNull(status.getLastEventAt()),
                    parseOutboundJson(status.getLastEventPayloadJson(), AgentExternalObservabilityMapper.View.EVENT),
                    parseOutboundJson(status.getPlanJson(), admin
                            ? AgentExternalObservabilityMapper.View.ADMIN
                            : AgentExternalObservabilityMapper.View.STATUS),
                    parseOutboundJson(status.getProgressJson(), admin
                            ? AgentExternalObservabilityMapper.View.ADMIN
                            : AgentExternalObservabilityMapper.View.STATUS),
                    admin ? parseOutboundJson(status.getObservabilityJson(), AgentExternalObservabilityMapper.View.ADMIN) : null,
                    parseOutboundJson(status.getObservabilitySummaryJson(), admin
                            ? AgentExternalObservabilityMapper.View.ADMIN
                            : AgentExternalObservabilityMapper.View.STATUS),
                    status.getObservabilityFullAvailable(),
                    Math.max(0, status.getTotalCreditsConsumed()),
                    status.getEventCount() > 0 ? status.getEventCount() : null,
                    status.getStartedAtMs() > 0 ? status.getStartedAtMs() : null,
                    status.getCompletedAtMs() > 0 ? status.getCompletedAtMs() : null,
                    status.getElapsedMs() > 0 ? status.getElapsedMs() : null
            ));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 agent status");
        } catch (Exception e) {
            return handleError(e, "查询 agent status");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/observability/full")
    public ResponseWrapper<Object> observabilityFull(Authentication authentication,
                                                     @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        if (!isAdmin(authentication)) {
            return ResponseWrapper.error(ResponseCode.FORBIDDEN,
                    "完整 observability 仅管理员可访问，请使用 traces、timeline 或 call detail");
        }
        try {
            AgentRunResultMessage result = loadRunResult(userId, runId, true);
            String observabilityJson = result.getObservabilityJson();
            if (observabilityJson != null
                    && observabilityJson.getBytes(StandardCharsets.UTF_8).length > OBSERVABILITY_FULL_MAX_BYTES) {
                return ResponseWrapper.error(
                        ResponseCode.BUSINESS_ERROR,
                        "observability 过大，请使用 /traces 或 /timeline 分页接口"
                );
            }
            Object observability = parseOutboundJson(observabilityJson, AgentExternalObservabilityMapper.View.ADMIN);
            if (observability == null) {
                return ResponseWrapper.error(ResponseCode.DATA_NOT_FOUND, "observability 不存在");
            }
            List<AgentArtifactResponse> artifacts = loadArtifactResponses(userId, runId, isAdmin(authentication));
            return ResponseWrapper.success(attachArtifactsToObservability(observability, runId, artifacts));
        } catch (RpcException e) {
            return handleRpcError(e, "查询完整 observability");
        } catch (Exception e) {
            return handleError(e, "查询完整 observability");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/traces")
    public ResponseWrapper<TraceListResponse> traces(Authentication authentication,
                                                     @PathVariable("runId") String runId,
                                                     @RequestParam(value = "type", required = false, defaultValue = "") String type,
                                                     @RequestParam(value = "phase", required = false, defaultValue = "") String phase,
                                                     @RequestParam(value = "after", required = false, defaultValue = "0") int after,
                                                     @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            boolean admin = isAdmin(authentication);
            AgentRunResultMessage result = loadRunResult(userId, runId, admin);
            String obsJson = result.getObservabilityJson();
            if (obsJson == null || obsJson.isBlank()) {
                return ResponseWrapper.success(new TraceListResponse(List.of(),
                        new TraceListResponse.TraceSummary(0, 0, 0, 0)));
            }

            Map<String, Object> obs = objectMapper.readValue(obsJson, Map.class);
            Map<String, Object> diagnostics = obs.get("diagnostics") instanceof Map
                    ? (Map<String, Object>) obs.get("diagnostics") : Map.of();
            Map<String, Object> summary = obs.get("summary") instanceof Map
                    ? (Map<String, Object>) obs.get("summary") : Map.of();

            List<TraceSpanItem> spans = new ArrayList<>();

            // LLM Traces
            Object llmTracesObj = diagnostics.get("llmTraces");
            if (llmTracesObj instanceof List<?> llmTraces) {
                for (Object item : llmTraces) {
                    if (item instanceof Map<?, ?> m) {
                        spans.add(TraceSpanItem.builder()
                                .type("llm")
                                .traceId(strVal(m.get("traceId")))
                                .time(strVal(m.get("time")))
                                .phase(strVal(m.get("phase")))
                                .todoId(emptyToNull(strVal(m.get("todoId"))))
                                .durationMs(longVal(m.get("durationMs")))
                                .model(strVal(m.get("model")))
                                .inputTokens(nullableLong(m.get("inputTokens")))
                                .outputTokens(nullableLong(m.get("outputTokens")))
                                .hasError(boolVal(m.get("hasError")))
                                .hasInputMessages(m.get("inputMessages") != null)
                                .hasReasoning(m.get("reasoningText") != null
                                        && !strVal(m.get("reasoningText")).isBlank())
                                .outputSummary(AgentExternalObservabilityMapper.safePreview(m.get("responsePreview"), 200))
                                .build());
                    }
                }
            }

            // Tool Traces
            Object toolTracesObj = diagnostics.get("toolTraces");
            if (toolTracesObj instanceof List<?> toolTraces) {
                for (Object item : toolTraces) {
                    if (item instanceof Map<?, ?> m) {
                        spans.add(TraceSpanItem.builder()
                                .type("tool")
                                .traceId(strVal(m.get("traceId")))
                                .time(strVal(m.get("time")))
                                .phase(strVal(m.get("phase")))
                                .todoId(emptyToNull(strVal(m.get("todoId"))))
                                .durationMs(longVal(m.get("durationMs")))
                                .toolName(strVal(m.get("toolName")))
                                .success(boolVal(m.get("success")))
                                .cacheHit(boolVal(m.get("cacheHit")))
                                .decisionLlmTraceId(emptyToNull(strVal(m.get("decisionLlmTraceId"))))
                                .outputSummary(AgentExternalObservabilityMapper.safePreview(m.get("outputPreview"), 200))
                                .build());
                    }
                }
            }

            String typeFilter = nvl(type).trim().toLowerCase();
            String phaseFilter = nvl(phase).trim().toLowerCase();
            if (!typeFilter.isBlank()) {
                spans.removeIf(s -> s.getType() == null || !typeFilter.equalsIgnoreCase(s.getType()));
            }
            if (!phaseFilter.isBlank()) {
                spans.removeIf(s -> s.getPhase() == null || !phaseFilter.equalsIgnoreCase(s.getPhase()));
            }

            // Sort by time, assign seq
            spans.sort(Comparator.comparing(s -> s.getTime() == null ? "" : s.getTime()));
            AtomicInteger seqCounter = new AtomicInteger(1);
            spans.forEach(s -> s.setSeq(seqCounter.getAndIncrement()));
            int safeAfter = Math.max(0, after);
            int safeLimit = Math.min(Math.max(1, limit), 500);
            spans = spans.stream()
                    .filter(s -> s.getSeq() > safeAfter)
                    .limit(safeLimit)
                    .toList();

            TraceListResponse.TraceSummary traceSummary = new TraceListResponse.TraceSummary(
                    longVal(summary.get("llmCalls")),
                    longVal(summary.get("toolCalls")),
                    longVal(summary.get("totalDurationMs")),
                    longVal(summary.get("totalTokens"))
            );

            return ResponseWrapper.success(new TraceListResponse(spans, traceSummary));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 traces");
        } catch (Exception e) {
            return handleError(e, "查询 traces");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/traces/{traceId}")
    public ResponseWrapper<TraceDetailResponse> traceDetail(Authentication authentication,
                                                            @PathVariable("runId") String runId,
                                                            @PathVariable("traceId") String traceId,
                                                            @RequestParam(value = "full", defaultValue = "false") boolean full,
                                                            @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        boolean admin = isAdmin(authentication);
        if (full && !admin) {
            return ResponseWrapper.error(ResponseCode.FORBIDDEN, "完整 trace 仅管理员可访问");
        }
        try {
            AgentRunResultMessage result = loadRunResult(userId, runId, admin);
            String obsJson = result.getObservabilityJson();
            if (obsJson == null || obsJson.isBlank()) {
                return ResponseWrapper.error(ResponseCode.DATA_NOT_FOUND, "trace 不存在");
            }

            Map<String, Object> obs = objectMapper.readValue(obsJson, Map.class);
            Map<String, Object> diagnostics = obs.get("diagnostics") instanceof Map
                    ? (Map<String, Object>) obs.get("diagnostics") : Map.of();

            // Search in LLM Traces
            Object llmTracesObj = diagnostics.get("llmTraces");
            if (llmTracesObj instanceof List<?> llmTraces) {
                for (Object item : llmTraces) {
                    if (item instanceof Map<?, ?> m && traceId.equals(strVal(m.get("traceId")))) {
                        TraceDetailResponse response = TraceDetailResponse.builder()
                                .type("llm")
                                .traceId(strVal(m.get("traceId")))
                                .phase(strVal(m.get("phase")))
                                .todoId(emptyToNull(strVal(m.get("todoId"))))
                                .todoSequence(m.get("todoSequence") instanceof Number n ? n.intValue() : null)
                                .time(strVal(m.get("time")))
                                .durationMs(longVal(m.get("durationMs")))
                                .model(strVal(m.get("model")))
                                .endpoint(AgentExternalObservabilityMapper.safePreview(m.get("endpoint"), 2000))
                                .inputTokens(nullableLong(m.get("inputTokens")))
                                .outputTokens(nullableLong(m.get("outputTokens")))
                                .cachedTokens(m.get("cachedTokens") instanceof Number n ? n.intValue() : null)
                                .actualCost(m.get("actualCost") instanceof Number n ? n.doubleValue() : null)
                                .inputMessages(null)
                                .outputText(null)
                                .reasoningText(null)
                                .hasError(boolVal(m.get("hasError")))
                                .error(emptyToNull(AgentExternalObservabilityMapper.safePreview(m.get("error"), 2000)))
                                .attempts(null)
                                .httpRequest(null)
                                .httpResponse(null)
                                .curlCommand(null)
                                .build();
                        return full ? enrichFullTraceResponse(response, runId, traceId, m, maxPartSize)
                                : ResponseWrapper.success(response);
                    }
                }
            }

            // Search in Tool Traces
            Object toolTracesObj = diagnostics.get("toolTraces");
            if (toolTracesObj instanceof List<?> toolTraces) {
                for (Object item : toolTraces) {
                    if (item instanceof Map<?, ?> m && traceId.equals(strVal(m.get("traceId")))) {
                        TraceDetailResponse response = TraceDetailResponse.builder()
                                .type("tool")
                                .traceId(strVal(m.get("traceId")))
                                .phase(strVal(m.get("phase")))
                                .todoId(emptyToNull(strVal(m.get("todoId"))))
                                .todoSequence(m.get("todoSequence") instanceof Number n ? n.intValue() : null)
                                .time(strVal(m.get("time")))
                                .durationMs(longVal(m.get("durationMs")))
                                .error(emptyToNull(AgentExternalObservabilityMapper.safePreview(m.get("error"), 2000)))
                                .toolName(strVal(m.get("toolName")))
                                .params(null)
                                .output(null)
                                .success(boolVal(m.get("success")))
                                .cacheHit(boolVal(m.get("cacheHit")))
                                .cacheKey(null)
                                .decisionLlmTraceId(emptyToNull(strVal(m.get("decisionLlmTraceId"))))
                                .decisionExcerpt(null)
                                .build();
                        return full ? enrichFullTraceResponse(response, runId, traceId, m, maxPartSize)
                                : ResponseWrapper.success(response);
                    }
                }
            }

            return ResponseWrapper.error(ResponseCode.DATA_NOT_FOUND, "trace 不存在");
        } catch (RpcException e) {
            return handleRpcError(e, "查询 trace 详情");
        } catch (Exception e) {
            return handleError(e, "查询 trace 详情");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/llm-calls/{llmCallId}/detail")
    public ResponseWrapper<AgentCallDetailResponse> llmCallDetail(Authentication authentication,
                                                                @PathVariable("runId") String runId,
                                                                @PathVariable("llmCallId") String llmCallId,
                                                                @RequestParam(value = "includeThinking", defaultValue = "false") boolean includeThinking) {
        // 强校验：thinking 字段只对 admin 开放；非 admin 即使传 true 也按 false 处理（不抛错，避免破坏普通用户调用）
        boolean effectiveIncludeThinking = includeThinking && isAdmin(authentication);
        return safeCallDetail(authentication, runId, "llm", llmCallId, effectiveIncludeThinking);
    }

    @GetMapping(AGENT_RUNS + "/{runId}/tool-calls/{toolCallId}/detail")
    public ResponseWrapper<AgentCallDetailResponse> toolCallDetail(Authentication authentication,
                                                                   @PathVariable("runId") String runId,
                                                                   @PathVariable("toolCallId") String toolCallId) {
        return safeCallDetail(authentication, runId, "tool", toolCallId, false);
    }

    private ResponseWrapper<AgentCallDetailResponse> safeCallDetail(Authentication authentication,
                                                                  String runId,
                                                                  String type,
                                                                  String callId,
                                                                  boolean includeThinking) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentRunResultMessage result = loadRunResult(userId, runId, isAdmin(authentication));
            String obsJson = result.getObservabilityJson();
            Map<String, Object> diagnostics = AgentCallDetailMapper.parseDiagnostics(obsJson);
            if ("llm".equals(type)) {
                return AgentCallDetailMapper.findLlmTrace(diagnostics, callId)
                        .map(trace -> ResponseWrapper.success(
                                AgentCallDetailMapper.resolveLlmDetail(
                                        trace,
                                        callId,
                                        runId,
                                        callDetailBlobReader.loadLlmCallDetail(runId, callId),
                                        includeThinking)))
                        .orElseGet(() -> ResponseWrapper.success(
                                AgentCallDetailMapper.unavailable("llm", callId, runId)));
            }
            return AgentCallDetailMapper.findToolTrace(diagnostics, callId)
                    .map(trace -> ResponseWrapper.success(
                            AgentCallDetailMapper.resolveToolDetail(
                                    trace,
                                    callId,
                                    runId,
                                    callDetailBlobReader.loadToolCallDetail(runId, callId))))
                    .orElseGet(() -> ResponseWrapper.success(
                            AgentCallDetailMapper.unavailable("tool", callId, runId)));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 call 详情");
        } catch (Exception e) {
            return handleError(e, "查询 call 详情");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/traces/{traceId}/full/parts")
    public ResponseWrapper<TraceDetailResponse.FullDetailParts> traceFullParts(Authentication authentication,
                                                                              @PathVariable("runId") String runId,
                                                                              @PathVariable("traceId") String traceId,
                                                                              @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        if (!isAdmin(authentication)) {
            return ResponseWrapper.error(ResponseCode.FORBIDDEN, "完整 trace parts 仅管理员可访问");
        }
        try {
            AgentRunResultMessage result = loadRunResult(userId, runId, true);
            Optional<TraceLookup> lookup = findTrace(result.getObservabilityJson(), traceId);
            if (lookup.isEmpty()) {
                return ResponseWrapper.error(ResponseCode.DATA_NOT_FOUND, "trace 不存在");
            }
            AgentRawTraceDetailMapper.FullTracePayload payload = resolveFullPayload(runId, traceId, lookup.get())
                    .orElse(null);
            if (payload == null) {
                return rawTraceMissingResponse(runId, traceId, lookup.get());
            }
            return ResponseWrapper.success(buildFullParts(runId, traceId, payload, maxPartSize));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 trace full parts");
        } catch (Exception e) {
            return handleError(e, "查询 trace full parts");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/traces/{traceId}/full/parts/{partIndex}")
    public ResponseEntity<byte[]> traceFullPart(Authentication authentication,
                                                @PathVariable("runId") String runId,
                                                @PathVariable("traceId") String traceId,
                                                @PathVariable("partIndex") int partIndex,
                                                @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return traceFullPartError(401, "UNAUTHORIZED");
        }
        if (!isAdmin(authentication)) {
            return traceFullPartError(403, "FORBIDDEN");
        }
        try {
            AgentRunResultMessage result = loadRunResult(userId, runId, true);
            Optional<TraceLookup> lookup = findTrace(result.getObservabilityJson(), traceId);
            if (lookup.isEmpty()) {
                return traceFullPartError(404, "TRACE_NOT_FOUND");
            }
            AgentRawTraceDetailMapper.FullTracePayload payload = resolveFullPayload(runId, traceId, lookup.get())
                    .orElse(null);
            if (payload == null) {
                boolean expired = rawTraceExpired(runId, traceId, lookup.get());
                return traceFullPartError(expired ? 410 : 404,
                        expired ? "RAW_TRACE_EXPIRED" : "RAW_TRACE_NOT_FOUND");
            }
            PreparedFullParts prepared = prepareFullParts(payload, maxPartSize);
            if (partIndex < 0 || partIndex >= prepared.totalParts()) {
                return traceFullPartError(400, "PART_INDEX_OUT_OF_RANGE");
            }
            byte[] content = Arrays.copyOfRange(
                    prepared.compressed(),
                    partIndex * prepared.partSize(),
                    Math.min(prepared.compressed().length, (partIndex + 1) * prepared.partSize()));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(content.length);
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
            headers.set("X-Trace-Full-Run-Id", nvl(runId));
            headers.set("X-Trace-Full-Trace-Id", nvl(traceId));
            headers.set("X-Trace-Full-Compression", "gzip");
            headers.set("X-Trace-Full-Part-Index", String.valueOf(partIndex));
            headers.set("X-Trace-Full-Part-Size", String.valueOf(prepared.partSize()));
            headers.set("X-Trace-Full-Total-Parts", String.valueOf(prepared.totalParts()));
            headers.set("X-Trace-Full-Checksum", prepared.checksum());
            return ResponseEntity.ok().headers(headers).body(content);
        } catch (RpcException e) {
            log.error("查询 trace full part 失败: runId={}, traceId={}, err={}", runId, traceId, e.getMessage());
            return traceFullPartError(resolveArtifactPartErrorStatus(e.getMessage()), "RPC_ERROR");
        } catch (Exception e) {
            log.error("查询 trace full part 失败: runId={}, traceId={}", runId, traceId, e);
            return traceFullPartError(500, "ERROR");
        }
    }

    private ResponseWrapper<TraceDetailResponse> enrichFullTraceResponse(TraceDetailResponse response,
                                                                         String runId,
                                                                         String traceId,
                                                                         Map<?, ?> trace,
                                                                         int maxPartSize) {
        TraceLookup lookup = new TraceLookup(response.getType(), (Map<?, ?>) trace);
        Optional<AgentRawTraceDetailMapper.FullTracePayload> payloadOpt = resolveFullPayload(runId, traceId, lookup);
        if (payloadOpt.isEmpty()) {
            return rawTraceMissingResponse(runId, traceId, lookup);
        }
        AgentRawTraceDetailMapper.FullTracePayload payload = payloadOpt.get();
        if (payload.thresholdBytes().length <= INLINE_FULL_MAX_BYTES) {
            response.setFullDetail(payload.fullDetail());
        } else {
            response.setFullDetailParts(buildFullParts(runId, traceId, payload, maxPartSize));
        }
        return ResponseWrapper.success(response);
    }

    private Optional<AgentRawTraceDetailMapper.FullTracePayload> resolveFullPayload(String runId,
                                                                                   String traceId,
                                                                                   TraceLookup lookup) {
        if ("llm".equals(lookup.type())) {
            Optional<String> raw = callDetailBlobReader.loadLlmCallRawContent(runId, traceId);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> meta = callDetailBlobReader.loadLlmCallRawMeta(runId, traceId)
                    .map(json -> AgentRawTraceDetailMapper.parseMeta(objectMapper, json))
                    .orElseGet(LinkedHashMap::new);
            return Optional.of(AgentRawTraceDetailMapper.buildLlmPayload(objectMapper, runId, traceId, raw.get(), meta));
        }
        if ("tool".equals(lookup.type())) {
            Optional<String> detail = callDetailBlobReader.loadToolCallDetail(runId, traceId);
            return detail.map(json -> AgentRawTraceDetailMapper.buildToolPayload(objectMapper, runId, traceId, json));
        }
        return Optional.empty();
    }

    private <T> ResponseWrapper<T> rawTraceMissingResponse(String runId, String traceId, TraceLookup lookup) {
        if (rawTraceExpired(runId, traceId, lookup)) {
            return ResponseWrapper.error(ResponseCode.DATA_EXPIRED, "RAW_TRACE_EXPIRED");
        }
        return ResponseWrapper.error(ResponseCode.DATA_NOT_FOUND, "RAW_TRACE_NOT_FOUND");
    }

    private boolean rawTraceExpired(String runId, String traceId, TraceLookup lookup) {
        if ("llm".equals(lookup.type())) {
            Optional<String> metaJson = callDetailBlobReader.loadLlmCallRawMeta(runId, traceId);
            if (metaJson.isEmpty()) {
                return false;
            }
            Map<String, Object> meta = AgentRawTraceDetailMapper.parseMeta(objectMapper, metaJson.get());
            Long expiresAt = nullableLong(meta.get("expiresAtMillis"));
            return expiresAt != null && System.currentTimeMillis() > expiresAt;
        }
        return boolVal(lookup.trace().get("detailBlobStored"));
    }

    private TraceDetailResponse.FullDetailParts buildFullParts(String runId,
                                                               String traceId,
                                                               AgentRawTraceDetailMapper.FullTracePayload payload,
                                                               int maxPartSize) {
        PreparedFullParts prepared = prepareFullParts(payload, maxPartSize);
        return new TraceDetailResponse.FullDetailParts(
                AGENT_RUNS + "/" + runId + "/traces/" + traceId + "/full/parts?maxPartSize=" + prepared.partSize(),
                prepared.partSize(),
                prepared.totalParts(),
                (long) payload.fullDetailBytes().length,
                (long) prepared.compressed().length,
                "gzip",
                prepared.checksum(),
                nullableLong(payload.fullDetail().get("createdAtMillis")),
                nullableLong(payload.fullDetail().get("expiresAtMillis"))
        );
    }

    private PreparedFullParts prepareFullParts(AgentRawTraceDetailMapper.FullTracePayload payload, int maxPartSize) {
        byte[] compressed = gzip(payload.fullDetailBytes());
        int partSize = resolveTraceFullPartSize(maxPartSize);
        int totalParts = compressed.length == 0 ? 0 : (compressed.length + partSize - 1) / partSize;
        return new PreparedFullParts(compressed, partSize, totalParts, md5Hex(compressed));
    }

    private Optional<TraceLookup> findTrace(String obsJson, String traceId) {
        if (obsJson == null || obsJson.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> obs = objectMapper.readValue(obsJson, Map.class);
            Map<String, Object> diagnostics = obs.get("diagnostics") instanceof Map
                    ? (Map<String, Object>) obs.get("diagnostics") : Map.of();
            Object llmTracesObj = diagnostics.get("llmTraces");
            if (llmTracesObj instanceof List<?> llmTraces) {
                for (Object item : llmTraces) {
                    if (item instanceof Map<?, ?> m && traceId.equals(strVal(m.get("traceId")))) {
                        return Optional.of(new TraceLookup("llm", m));
                    }
                }
            }
            Object toolTracesObj = diagnostics.get("toolTraces");
            if (toolTracesObj instanceof List<?> toolTraces) {
                for (Object item : toolTraces) {
                    if (item instanceof Map<?, ?> m && traceId.equals(strVal(m.get("traceId")))) {
                        return Optional.of(new TraceLookup("tool", m));
                    }
                }
            }
        } catch (Exception ignore) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private int resolveTraceFullPartSize(int requestedPartSize) {
        int effective = requestedPartSize > 0 ? requestedPartSize : TRACE_FULL_DEFAULT_PART_SIZE;
        return Math.max(TRACE_FULL_MIN_PART_SIZE, Math.min(TRACE_FULL_MAX_PART_SIZE, effective));
    }

    private ResponseEntity<byte[]> traceFullPartError(int status, String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(0);
        headers.set("X-Trace-Full-Error", code);
        return ResponseEntity.status(status).headers(headers).body(new byte[0]);
    }

    private byte[] gzip(byte[] raw) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length);
             GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(raw);
            gzip.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("failed to gzip trace full detail", e);
        }
    }

    private String md5Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute trace full checksum", e);
        }
    }

    private record TraceLookup(String type, Map<?, ?> trace) {
    }

    private record PreparedFullParts(byte[] compressed, int partSize, int totalParts, String checksum) {
    }

    @GetMapping(AGENT_RUNS + "/{runId}/artifacts")
    public ResponseWrapper<List<AgentArtifactResponse>> artifacts(Authentication authentication,
                                                                  @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            return ResponseWrapper.success(loadArtifactResponses(userId, runId, isAdmin(authentication)));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 artifacts");
        } catch (Exception e) {
            return handleError(e, "查询 artifacts");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/artifacts/{artifactId}/parts")
    public ResponseWrapper<AgentArtifactPartsMetaResponse> artifactParts(Authentication authentication,
                                                                        @PathVariable("runId") String runId,
                                                                        @PathVariable("artifactId") String artifactId,
                                                                        @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentArtifactPartsMetaMessage meta = resolveService().getArtifactPartsMeta(
                    GetAgentArtifactPartsRequest.newBuilder()
                            .setUserId(userId)
                            .setArtifactId(artifactId)
                            .setMaxPartSize(maxPartSize)
                            .setIsAdmin(isAdmin(authentication))
                            .build()
            );
            return ResponseWrapper.success(toArtifactPartsMetaResponse(meta));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 artifact parts");
        } catch (Exception e) {
            return handleError(e, "查询 artifact parts");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/artifacts/{artifactId}/parts/{partIndex}")
    public ResponseEntity<byte[]> artifactPart(Authentication authentication,
                                               @PathVariable("runId") String runId,
                                               @PathVariable("artifactId") String artifactId,
                                               @PathVariable("partIndex") int partIndex,
                                               @RequestParam(value = "maxPartSize", required = false, defaultValue = "0") int maxPartSize) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return artifactPartError(401, "UNAUTHORIZED");
        }
        if (runId == null || runId.isBlank()) {
            return artifactPartError(400, "BAD_REQUEST");
        }
        try {
            AgentArtifactPartMessage part = resolveService().getArtifactPart(
                    GetAgentArtifactPartRequest.newBuilder()
                            .setUserId(userId)
                            .setArtifactId(artifactId)
                            .setPartIndex(partIndex)
                            .setMaxPartSize(maxPartSize)
                            .setIsAdmin(isAdmin(authentication))
                            .build()
            );
            byte[] content = part.getContent().toByteArray();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(content.length);
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
            headers.set("X-Artifact-Id", nvl(part.getArtifactId()));
            headers.set("X-Artifact-Filename", nvl(part.getFilename()));
            headers.set("X-Artifact-Content-Type", nvl(part.getContentType()));
            headers.set("X-Artifact-Compression", nvl(part.getCompression()));
            headers.set("X-Artifact-Part-Index", String.valueOf(part.getPartIndex()));
            headers.set("X-Artifact-Part-Size", String.valueOf(part.getPartSize()));
            headers.set("X-Artifact-Total-Parts", String.valueOf(part.getTotalParts()));
            return ResponseEntity.ok().headers(headers).body(content);
        } catch (RpcException e) {
            log.error("查询 artifact part 失败: runId={}, artifactId={}, err={}", runId, artifactId, e.getMessage());
            return artifactPartError(resolveArtifactPartErrorStatus(e.getMessage()), "RPC_ERROR");
        } catch (Exception e) {
            log.error("查询 artifact part 失败: runId={}, artifactId={}", runId, artifactId, e);
            return artifactPartError(resolveArtifactPartErrorStatus(e.getMessage()), "ERROR");
        }
    }

    @GetMapping(AGENT_RUNS + "/{runId}/artifacts/{artifactId}/download")
    public ResponseEntity<byte[]> downloadArtifact(Authentication authentication,
                                                   @PathVariable("runId") String runId,
                                                   @PathVariable("artifactId") String artifactId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        if (runId == null || runId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            DownloadAgentArtifactResponse resp = resolveService().downloadArtifact(
                    DownloadAgentArtifactRequest.newBuilder()
                            .setUserId(userId)
                            .setArtifactId(artifactId)
                            .setIsAdmin(isAdmin(authentication))
                            .build()
            );
            HttpHeaders headers = new HttpHeaders();
            MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
            try {
                if (resp.getContentType() != null && !resp.getContentType().isBlank()) {
                    mediaType = MediaType.parseMediaType(resp.getContentType());
                }
            } catch (Exception ignore) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
            headers.setContentType(mediaType);
            headers.setContentLength(resp.getContent().size());
            String filename = resp.getFilename() == null || resp.getFilename().isBlank() ? "artifact.bin" : resp.getFilename();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            return ResponseEntity.ok().headers(headers).body(resp.getContent().toByteArray());
        } catch (RpcException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("artifact not found") || msg.contains("run not found")) {
                return ResponseEntity.status(404).build();
            }
            if (msg.contains("artifact too large")) {
                return ResponseEntity.status(422).build();
            }
            log.error("下载 artifact 失败: runId={}, artifactId={}, err={}", runId, artifactId, e.getMessage());
            return ResponseEntity.status(502).build();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("artifact not found") || msg.contains("run not found")) {
                return ResponseEntity.status(404).build();
            }
            if (msg.contains("artifact too large")) {
                return ResponseEntity.status(422).build();
            }
            log.error("下载 artifact 失败: runId={}, artifactId={}", runId, artifactId, e);
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping(AGENT_RUNS + "/{runId}/feedback")
    public ResponseWrapper<String> feedback(Authentication authentication,
                                           @PathVariable("runId") String runId,
                                           @RequestBody(required = false) AgentFeedbackRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            int rating = request == null || request.rating() == null ? 0 : request.rating();
            String tagsJson = request == null ? "" : objectMapper.writeValueAsString(request.tags());
            String payloadJson = request == null ? "" : objectMapper.writeValueAsString(request.payload());
            resolveService().submitFeedback(
                    SubmitAgentFeedbackRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setRating(rating)
                            .setComment(nvl(request == null ? null : request.comment()))
                            .setTagsJson(tagsJson == null ? "" : tagsJson)
                            .setPayloadJson(payloadJson == null ? "" : payloadJson)
                            .build()
            );
            return ResponseWrapper.success("ok");
        } catch (RpcException e) {
            return handleRpcError(e, "提交 feedback");
        } catch (Exception e) {
            return handleError(e, "提交 feedback");
        }
    }

    @PostMapping(AGENT_RUNS + "/{runId}:export")
    public ResponseWrapper<AgentExportResponse> export(Authentication authentication,
                                                      @PathVariable("runId") String runId,
                                                      @RequestBody(required = false) AgentExportRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        String format = request == null || request.format() == null ? "" : request.format().trim();
        try {
            ExportAgentRunResponse resp = resolveService().exportRun(
                    ExportAgentRunRequest.newBuilder().setUserId(userId).setId(runId).setFormat(nvl(format)).build()
            );
            return ResponseWrapper.success(new AgentExportResponse(
                    resp.getExportId(),
                    resp.getStatus(),
                    emptyToNull(resp.getUrl()),
                    emptyToNull(resp.getMessage())
            ));
        } catch (RpcException e) {
            return handleRpcError(e, "导出 agent run");
        } catch (Exception e) {
            return handleError(e, "导出 agent run");
        }
    }

    /**
     * 发送追问消息。
     * <p>
     * 仅支持 COMPLETED 状态的 Run 进行追问。
     *
     * @param authentication 当前用户认证信息
     * @param runId          Run ID
     * @param request        发送消息请求
     * @return 发送结果
     */
    @PostMapping(AGENT_RUNS + "/{runId}/messages")
    public ResponseWrapper<AgentMessageSendResponse> sendMessage(Authentication authentication,
                                                                 @PathVariable("runId") String runId,
                                                                 @RequestBody AgentMessageSendRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        if (request == null || request.content() == null || request.content().isBlank()) {
            return ResponseWrapper.paramError("content 不能为空");
        }

        // 处理 contextOverride：仅 admin + debugMode 可用
        String contextOverride = null;
        if (request.contextOverride() != null && !request.contextOverride().isBlank()) {
            boolean admin = isAdmin(authentication);
            boolean debugMode = Boolean.TRUE.equals(request.debugMode());
            if (admin && debugMode) {
                contextOverride = request.contextOverride();
            } else {
                log.warn("Non-admin or non-debug user attempted to use contextOverride, userId={}", userId);
            }
        }

        try {
            SendAgentMessageResponse resp = resolveService().sendMessage(
                    SendAgentMessageRequest.newBuilder()
                            .setUserId(userId)
                            .setRunId(runId)
                            .setContent(request.content())
                            .setContextOverride(nvl(contextOverride))
                            .setStream(Boolean.TRUE.equals(request.stream()))
                            .build()
            );
            return ResponseWrapper.success(new AgentMessageSendResponse(
                    resp.getMessageId(),
                    resp.getSeq(),
                    resp.getStatus(),
                    emptyToNull(resp.getRunStatus()),
                    emptyToNull(resp.getRejectReason())
            ));
        } catch (RpcException e) {
            return handleRpcError(e, "发送消息");
        } catch (Exception e) {
            return handleError(e, "发送消息");
        }
    }

    /**
     * 获取 Run 的消息历史。
     *
     * @param authentication   当前用户认证信息
     * @param runId            Run ID
     * @param limit            每页数量（默认 50，最大 200）
     * @param offset           分页偏移
     * @param includeInitial   是否包含初始问题（默认 true）
     * @return 消息历史列表
     */
    @GetMapping(AGENT_RUNS + "/{runId}/messages")
    public ResponseWrapper<AgentMessageListResponse> listMessages(Authentication authentication,
                                                                  @PathVariable("runId") String runId,
                                                                  @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
                                                                  @RequestParam(value = "offset", required = false, defaultValue = "0") int offset,
                                                                  @RequestParam(value = "include_initial", required = false, defaultValue = "true") boolean includeInitial) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            ListAgentMessagesResponse resp = resolveService().listMessages(
                    ListAgentMessagesRequest.newBuilder()
                            .setUserId(userId)
                            .setRunId(runId)
                            .setLimit(Math.min(Math.max(1, limit), 200))
                            .setOffset(Math.max(0, offset))
                            .setIncludeInitial(includeInitial)
                            .build()
            );
            List<AgentMessageItemResponse> items = new ArrayList<>();
            for (var item : resp.getItemsList()) {
                items.add(new AgentMessageItemResponse(
                        item.getId(),
                        item.getSeq(),
                        item.getRole(),
                        item.getContent(),
                        item.getMsgType(),
                        emptyToNull(item.getMetaJson()),
                        emptyToNull(item.getCreatedAt())
                ));
            }
            return ResponseWrapper.success(new AgentMessageListResponse(
                    items,
                    resp.getTotal(),
                    resp.getHasMore()
            ));
        } catch (RpcException e) {
            return handleRpcError(e, "查询消息历史");
        } catch (Exception e) {
            return handleError(e, "查询消息历史");
        }
    }

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

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        String username = authentication.getName();
        User user = authService.getUserByUsername(username);
        if (user == null) {
            return false;
        }
        Integer userType = user.getUserType();
        return userType != null && userType == ADMIN_USER_TYPE;
    }

    private int resolveLimit(Integer limit, Integer size, Integer max) {
        if (limit != null && limit > 0) {
            return limit;
        }
        if (max != null && max > 0) {
            return max;
        }
        if (size != null && size > 0) {
            return size;
        }
        return 20;
    }

    private int resolveOffset(Integer offset, Integer page, int limit) {
        if (offset != null && offset >= 0) {
            return offset;
        }
        if (page != null && page > 0 && limit > 0) {
            long candidate = (long) page * (long) limit;
            return candidate > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) candidate;
        }
        return 0;
    }

    private AgentRunResponse toRunResponse(AgentRunMessage run, boolean isAdmin) {
        return new AgentRunResponse(
                run.getId(),
                run.getStatus(),
                run.getCurrentStep(),
                run.getMaxSteps(),
                parseOutboundJson(run.getPlanJson(), isAdmin
                        ? AgentExternalObservabilityMapper.View.ADMIN
                        : AgentExternalObservabilityMapper.View.STATUS),
                parseOutboundJson(run.getSnapshotJson(), isAdmin
                        ? AgentExternalObservabilityMapper.View.ADMIN
                        : AgentExternalObservabilityMapper.View.RUN_SNAPSHOT),
                emptyToNull(AgentExternalObservabilityMapper.safePreview(run.getLastError(), 10_000)),
                emptyToNull(run.getTtlExpiresAt()),
                emptyToNull(run.getStartedAt()),
                emptyToNull(run.getUpdatedAt()),
                emptyToNull(run.getCompletedAt()),
                emptyToNull(AgentExternalObservabilityMapper.parseToJson(
                        objectMapper,
                        run.getExt(),
                        isAdmin
                                ? AgentExternalObservabilityMapper.View.ADMIN
                                : AgentExternalObservabilityMapper.View.STATUS)),
                streamUrl(run.getId())
        );
    }

    private String streamUrl(String runId) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        return AGENT_RUNS + "/" + runId + "/stream";
    }

    private AgentSnapshotPartsMetaResponse toSnapshotPartsMetaResponse(AgentSnapshotPartsMetaMessage meta) {
        return new AgentSnapshotPartsMetaResponse(
                meta.getRunId(),
                meta.getPartSize(),
                meta.getTotalParts(),
                meta.getUncompressedSize(),
                meta.getCompressedSize(),
                emptyToNull(meta.getCompression()),
                emptyToNull(meta.getChecksum())
        );
    }

    private AgentArtifactPartsMetaResponse toArtifactPartsMetaResponse(AgentArtifactPartsMetaMessage meta) {
        return new AgentArtifactPartsMetaResponse(
                meta.getArtifactId(),
                emptyToNull(meta.getFilename()),
                emptyToNull(meta.getContentType()),
                meta.getPartSize(),
                meta.getTotalParts(),
                meta.getUncompressedSize(),
                meta.getCompressedSize(),
                emptyToNull(meta.getCompression()),
                emptyToNull(meta.getChecksum())
        );
    }

    private List<AgentArtifactResponse> loadArtifactResponses(String userId, String runId, boolean isAdmin) {
        ListAgentArtifactsResponse resp = resolveService().listArtifacts(
                ListAgentArtifactsRequest.newBuilder()
                        .setUserId(userId)
                        .setId(runId)
                        .setIsAdmin(isAdmin)
                        .build()
        );
        List<AgentArtifactResponse> items = new ArrayList<>();
        for (var a : resp.getItemsList()) {
            String metaJson = AgentExternalObservabilityMapper.parseToJson(
                    objectMapper,
                    a.getMetaJson(),
                    isAdmin
                            ? AgentExternalObservabilityMapper.View.ADMIN
                            : AgentExternalObservabilityMapper.View.STRUCTURED);
            items.add(new AgentArtifactResponse(
                    a.getArtifactId(),
                    a.getType(),
                    a.getName(),
                    a.getContentType(),
                    a.getUrl(),
                    emptyToNull(metaJson),
                    emptyToNull(a.getCreatedAt()),
                    a.getExpiresAtMillis() <= 0 ? null : a.getExpiresAtMillis()
            ));
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private Object attachArtifactsToObservability(Object observability,
                                                  String runId,
                                                  List<AgentArtifactResponse> artifacts) {
        Map<String, Object> result;
        if (observability instanceof Map<?, ?> map) {
            result = new LinkedHashMap<>((Map<String, Object>) map);
        } else {
            result = new LinkedHashMap<>();
            result.put("observability", observability);
        }
        List<Map<String, Object>> artifactMaps = new ArrayList<>();
        List<Map<String, Object>> datasetArtifactMaps = new ArrayList<>();
        for (AgentArtifactResponse artifact : artifacts) {
            Map<String, Object> item = toArtifactMap(runId, artifact);
            artifactMaps.add(item);
            if (artifact.type() != null && artifact.type().startsWith("dataset_")) {
                datasetArtifactMaps.add(item);
            }
        }
        result.put("artifacts", artifactMaps);
        result.put("dataset_artifacts", datasetArtifactMaps);
        return result;
    }

    private Map<String, Object> toArtifactMap(String runId, AgentArtifactResponse artifact) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("artifact_id", artifact.artifactId());
        item.put("type", artifact.type());
        item.put("name", artifact.name());
        item.put("content_type", artifact.contentType());
        item.put("download_url", artifact.url());
        item.put("parts_url", AGENT_RUNS + "/" + runId + "/artifacts/" + artifact.artifactId() + "/parts");
        item.put("created_at", artifact.createdAt());
        item.put("expires_at_millis", artifact.expiresAtMillis());
        Object meta = parseOutboundJson(artifact.metaJson(), AgentExternalObservabilityMapper.View.ADMIN);
        item.put("meta", meta == null ? Map.of() : meta);
        return item;
    }

    private ResponseEntity<byte[]> snapshotPartError(int status, String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(0);
        headers.set("X-Snapshot-Error", code);
        return ResponseEntity.status(status).headers(headers).body(new byte[0]);
    }

    private ResponseEntity<byte[]> artifactPartError(int status, String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(0);
        headers.set("X-Artifact-Error", code);
        return ResponseEntity.status(status).headers(headers).body(new byte[0]);
    }

    private int resolveSnapshotPartErrorStatus(String message) {
        String msg = message == null ? "" : message.toLowerCase();
        if (msg.contains("run not found")) {
            return 404;
        }
        if (msg.contains("part_index") || msg.contains("out of range") || msg.contains("snapshot has no parts")) {
            return 400;
        }
        return 500;
    }

    private int resolveArtifactPartErrorStatus(String message) {
        String msg = message == null ? "" : message.toLowerCase();
        if (msg.contains("run not found") || msg.contains("artifact not found")) {
            return 404;
        }
        if (msg.contains("part_index") || msg.contains("out of range") || msg.contains("has no parts")) {
            return 400;
        }
        return 500;
    }

    private <T> ResponseWrapper<T> handleRpcError(RpcException e, String action) {
        log.error("{}失败: {}", action, e.getMessage());
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("run not found")) {
            return ResponseWrapper.error(ResponseCode.DATA_NOT_FOUND, "run 不存在");
        }
        if (msg.contains("run is running")) {
            return ResponseWrapper.error(ResponseCode.BUSINESS_ERROR, "run 运行中，请先停止后删除");
        }
        if (msg.contains("run expired")) {
            return ResponseWrapper.error(ResponseCode.BUSINESS_ERROR, "run 已过期，不能断点续跑，请新建 run");
        }
        if (msg.contains("snapshot_version_incompatible")) {
            return ResponseWrapper.error(
                    ResponseCode.BUSINESS_ERROR,
                    "断点版本不兼容，建议新建 run。详情: " + (e.getMessage() == null ? "" : e.getMessage())
            );
        }
        if (msg.contains("invalid status filter")) {
            return ResponseWrapper.error(ResponseCode.PARAM_ERROR, "status 参数非法");
        }
        return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, action + "失败");
    }

    private <T> ResponseWrapper<T> handleError(Exception e, String action) {
        log.error("{}失败", action, e);
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("run not found")) {
            return ResponseWrapper.error(ResponseCode.DATA_NOT_FOUND, "run 不存在");
        }
        if (msg.contains("run is running")) {
            return ResponseWrapper.error(ResponseCode.BUSINESS_ERROR, "run 运行中，请先停止后删除");
        }
        if (msg.contains("run expired")) {
            return ResponseWrapper.error(ResponseCode.BUSINESS_ERROR, "run 已过期，不能断点续跑，请新建 run");
        }
        if (msg.contains("snapshot_version_incompatible")) {
            return ResponseWrapper.error(
                    ResponseCode.BUSINESS_ERROR,
                    "断点版本不兼容，建议新建 run。详情: " + (e.getMessage() == null ? "" : e.getMessage())
            );
        }
        if (msg.contains("invalid status filter")) {
            return ResponseWrapper.error(ResponseCode.PARAM_ERROR, "status 参数非法");
        }
        return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, action + "失败");
    }

    private AgentRunResultMessage loadRunResult(String userId, String runId, boolean isAdmin) {
        return runResultCacheService.getRunResult(userId, runId, isAdmin);
    }

    private AgentRunCostResponse toCostResponse(AgentRunCostMessage cost) {
        return new AgentRunCostResponse(
                cost.getId(),
                cost.getHasTotalCost() ? cost.getTotalCost() : null,
                cost.getHasUpstreamInferenceCost() ? cost.getUpstreamInferenceCost() : null,
                cost.getHasCacheDiscount() ? cost.getCacheDiscount() : null,
                cost.getCostedCallCount(),
                cost.getTotalCallCount(),
                cost.getComplete(),
                emptyToNull(cost.getCurrency()),
                emptyToNull(cost.getSource()),
                emptyToNull(cost.getUpdatedAt()),
                cost.getPersisted(),
                cost.getCallsList().stream()
                        .map(call -> new AgentRunCostResponse.CallCost(
                                emptyToNull(call.getTraceId()),
                                emptyToNull(call.getGenerationId()),
                                emptyToNull(call.getPhase()),
                                emptyToNull(call.getTodoId()),
                                emptyToNull(call.getEndpoint()),
                                emptyToNull(call.getModel()),
                                call.getHasActualCost() ? call.getActualCost() : null,
                                call.getHasUpstreamInferenceCost() ? call.getUpstreamInferenceCost() : null,
                                call.getHasCacheDiscount() ? call.getCacheDiscount() : null,
                                call.getHasIsByok() ? call.getIsByok() : null,
                                call.getStartedAtMs() > 0 ? call.getStartedAtMs() : null,
                                call.getCompletedAtMs() > 0 ? call.getCompletedAtMs() : null,
                                emptyToNull(call.getSource())))
                        .toList()
        );
    }

    private AgentRunCreditsResponse toRunCreditsResponse(GetAgentRunCreditsResponse resp) {
        AgentRunCreditsResponse.SettlementSummary summary = null;
        if (resp.hasSummary()) {
            var s = resp.getSummary();
            summary = new AgentRunCreditsResponse.SettlementSummary(
                    s.getImmediateCount(),
                    s.getDelayedCount(),
                    s.getPendingCount(),
                    s.getMissingCount(),
                    s.getTotalCallCount(),
                    emptyToNull(s.getCurrency()),
                    emptyToNull(s.getTotalCredits()),
                    emptyToNull(s.getLastSettlementAt())
            );
        }
        return new AgentRunCreditsResponse(
                emptyToNull(resp.getRunId()),
                emptyToNull(resp.getOwnerUserId()),
                emptyToNull(resp.getTotalCredits()),
                emptyToNull(resp.getCurrency()),
                resp.getRecordsList().stream()
                        .map(rec -> new AgentRunCreditsResponse.CallRecord(
                                emptyToNull(rec.getCallId()),
                                emptyToNull(rec.getPhase()),
                                emptyToNull(rec.getTodoId()),
                                emptyToNull(rec.getEndpoint()),
                                emptyToNull(rec.getModel()),
                                emptyToNull(rec.getCostSource()),
                                emptyToNull(rec.getCurrency()),
                                emptyToNull(rec.getCostAmount()),
                                emptyToNull(rec.getCreditDelta()),
                                rec.getSettlementAttempt() > 0 ? rec.getSettlementAttempt() : null,
                                emptyToNull(rec.getSettlementStatus()),
                                emptyToNull(rec.getReason()),
                                emptyToNull(rec.getCreatedAt())))
                        .toList(),
                summary,
                emptyToNull(resp.getUpdatedAt())
        );
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private boolean hasPositiveCredit(User user) {
        BigDecimal credit = user == null ? null : user.getCredit();
        return credit != null && credit.signum() > 0;
    }

    private Object parseOutboundJson(String json, AgentExternalObservabilityMapper.View view) {
        return AgentExternalObservabilityMapper.parse(objectMapper, json, view);
    }

    @SuppressWarnings("unchecked")
    private void appendTraceTimelineItems(String userId,
                                          String runId,
                                          boolean isAdmin,
                                          List<TimelineResponse.TimelineItem> items,
                                          String minEventTime,
                                          String maxEventTime,
                                          int maxAdditionalItems) {
        if (maxAdditionalItems <= 0) {
            return;
        }
        try {
            AgentRunResultMessage result = loadRunResult(userId, runId, isAdmin);
            String observabilityJson = result.getObservabilityJson();
            if (observabilityJson == null || observabilityJson.isBlank()
                    || observabilityJson.getBytes(StandardCharsets.UTF_8).length > OBSERVABILITY_FULL_MAX_BYTES) {
                return;
            }
            Map<String, Object> obs = objectMapper.readValue(observabilityJson, Map.class);
            Map<String, Object> diagnostics = obs.get("diagnostics") instanceof Map
                    ? (Map<String, Object>) obs.get("diagnostics") : Map.of();
            AtomicInteger traceSeq = new AtomicInteger(1);
            appendTraceTimelineItems(items, diagnostics.get("llmTraces"), "llm", minEventTime, maxEventTime,
                    traceSeq, maxAdditionalItems);
            appendTraceTimelineItems(items, diagnostics.get("toolTraces"), "tool", minEventTime, maxEventTime,
                    traceSeq, maxAdditionalItems);
        } catch (Exception e) {
            log.debug("合并 timeline trace 失败: runId={}, error={}", runId, e.getMessage());
        }
    }

    private void appendTraceTimelineItems(List<TimelineResponse.TimelineItem> items,
                                          Object tracesObj,
                                          String traceType,
                                          String minEventTime,
                                          String maxEventTime,
                                          AtomicInteger traceSeq,
                                          int maxAdditionalItems) {
        if (!(tracesObj instanceof List<?> traces)) {
            return;
        }
        for (Object item : traces) {
            if (traceSeq.get() > maxAdditionalItems) {
                return;
            }
            if (!(item instanceof Map<?, ?> trace)) {
                continue;
            }
            Object mappedTrace = AgentExternalObservabilityMapper.sanitize(
                    trace, AgentExternalObservabilityMapper.View.EVENT);
            if (!(mappedTrace instanceof Map<?, ?> safeTrace)) {
                continue;
            }
            String traceTime = safeTimelineString(safeTrace.get("time"), 128);
            if (!withinTimelineWindow(traceTime, minEventTime, maxEventTime)) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("trace_id", safeTimelineString(safeTrace.get("traceId"), 512));
            detail.put("phase", safeTimelineString(safeTrace.get("phase"), 512));
            detail.put("todo_id", emptyToNull(safeTimelineString(safeTrace.get("todoId"), 512)));
            detail.put("duration_ms", longVal(safeTrace.get("durationMs")));
            if ("llm".equals(traceType)) {
                detail.put("model", safeTimelineString(safeTrace.get("model"), 512));
                detail.put("endpoint", safeTimelineString(safeTrace.get("endpoint"), 2000));
                detail.put("has_error", boolVal(safeTrace.get("hasError")));
                detail.put("input_tokens", nullableLong(safeTrace.get("inputTokens")));
                detail.put("output_tokens", nullableLong(safeTrace.get("outputTokens")));
            } else {
                detail.put("tool_name", safeTimelineString(safeTrace.get("toolName"), 512));
                detail.put("success", boolVal(safeTrace.get("success")));
                detail.put("cache_hit", boolVal(safeTrace.get("cacheHit")));
            }
            String safeTraceId = safeTimelineString(safeTrace.get("traceId"), 512);
            items.add(new TimelineResponse.TimelineItem(
                    -traceSeq.getAndIncrement(),
                    "trace",
                    safeTraceId,
                    traceType,
                    traceTime,
                    traceTimelineTitle(traceType, safeTrace),
                    longVal(safeTrace.get("durationMs")),
                    detail
            ));
        }
    }

    private boolean withinTimelineWindow(String time, String minEventTime, String maxEventTime) {
        if (time == null || time.isBlank()) {
            return false;
        }
        if (minEventTime == null || maxEventTime == null) {
            return true;
        }
        return time.compareTo(minEventTime) >= 0 && time.compareTo(maxEventTime) <= 0;
    }

    private String strVal(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safeTimelineString(Object value, int maxChars) {
        String safe = AgentExternalObservabilityMapper.safePreview(value, maxChars);
        return safe == null ? "" : safe;
    }

    private String traceTimelineTitle(String traceType, Map<?, ?> trace) {
        String phase = safeTimelineString(trace.get("phase"), 512);
        long durationMs = longVal(trace.get("durationMs"));
        if ("llm".equals(traceType)) {
            return safeTimelineString(
                    "LLM " + phase + " " + safeTimelineString(trace.get("model"), 512) + " " + durationMs + "ms",
                    120);
        }
        return safeTimelineString(
                "Tool " + phase + " " + safeTimelineString(trace.get("toolName"), 512) + " " + durationMs + "ms",
                120);
    }

    private String timelineTitle(String eventType, Object payload) {
        String type = nvl(eventType);
        if (payload instanceof Map<?, ?> map) {
            Object todo = map.get("todo_id");
            Object tool = map.get("tool");
            Object summary = map.get("summary");
            if (todo != null && !String.valueOf(todo).isBlank()) {
                return type + " " + todo;
            }
            if (tool != null && !String.valueOf(tool).isBlank()) {
                return type + " " + tool;
            }
            if (summary != null && !String.valueOf(summary).isBlank()) {
                return truncate(type + " " + summary, 120);
            }
        }
        return type;
    }

    private long longVal(Object value) {
        if (value instanceof Number n) return n.longValue();
        return 0L;
    }

    private Long nullableLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        return null;
    }

    private boolean boolVal(Object value) {
        if (value instanceof Boolean b) return b;
        return false;
    }

    private String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) return value;
        return value.substring(0, maxLen) + "...";
    }

    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private ParsedModelSelection parseModelSelection(String compositeModelId) {
        String normalized = nvl(compositeModelId).trim();
        if (normalized.isBlank()) {
            return new ParsedModelSelection("", "");
        }
        int idx = normalized.lastIndexOf('@');
        if (idx <= 0 || idx >= normalized.length() - 1) {
            return new ParsedModelSelection(normalized, "");
        }
        String modelName = normalized.substring(0, idx).trim();
        String endpointName = normalized.substring(idx + 1).trim();
        return new ParsedModelSelection(modelName, endpointName);
    }

    private record ParsedModelSelection(String modelName, String endpointName) {
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
