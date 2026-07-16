package world.willfrog.agentlangchain.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentArtifactService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityQuery;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityReadMode;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentModelCatalogService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentRunCostService;
import world.willfrog.agent.platform.service.AgentRunCreditQueryService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.platform.service.SnapshotPartService;
import world.willfrog.agent.platform.service.SnapshotPartsMeta;
import world.willfrog.agentlangchain.routing.LangchainSingleWriterGuard;
import world.willfrog.agentlangchain.tools.LangchainToolCatalogService;
import world.willfrog.alphafrogmicro.agent.idl.AgentEmpty;
import world.willfrog.alphafrogmicro.agent.idl.AgentFeatureConfigMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentModelMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRetentionConfigMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunListItemMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessageItem;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentSnapshotPartMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentSnapshotPartsMetaMessage;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentConfigRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentConfigResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCostRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.RefreshAgentRunCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentSnapshotPartRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentSnapshotPartsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentModelsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentModelsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentToolsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentToolsResponse;
import world.willfrog.alphafrogmicro.agent.idl.SubmitAgentFeedbackRequest;
import world.willfrog.alphafrogmicro.agent.idl.UpdateAgentRunRequest;

import com.google.protobuf.ByteString;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 读路径统一入口 —— 所有查询类 RPC 最终都委托到这里。
 *
 * <h2>在 agent 架构中的位置</h2>
 * 上一轮 Top5 覆盖的是"写路径"（创建 run → planning → 执行 → 落库）。
 * 本类覆盖"读路径"：前端轮询、matrix 脚本、用户查看历史 run 等所有查询操作。
 * 理解 agent 完整请求链路必须读写两路径都看。
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li>run 查询（单个 run 详情、列表分页）</li>
 *   <li>status 轮询（前端 matrix 最频繁调用的接口，含 phase 推断、计划进度、
 *       observability 摘要）</li>
 *   <li>events 增量拉取（通过 afterSeq 游标实现断点续传；当前 Redis 为主、DB 为过渡期兜底）</li>
 *   <li>result 结果查询（含结构化答案、credits 消耗计算）</li>
 *   <li>配置类查询（可用模型列表、工具列表、credits 余额）</li>
 *   <li>快照分段下载（大 run 的 snapshot 拆成多 part，分段拉取避免 OOM）</li>
 * </ul>
 *
 * <h2>读写一致性</h2>
 * langchain 服务和 agent runtime 共享同一套 PG/Redis 存储。事件流目前由
 * {@link AgentEventService} 优先从 Redis ZSET 读取，只有 Redis 没有该 run 的事件时才回退 DB；
 * 这是压测后为了避免大量 event 长期堆在数据库中的过渡设计。
 * 本类的读操作依赖 {@link LangchainSingleWriterGuard} 保证：
 * 当前 langchain 实例有写入权时才允许直接读 PG，避免读到过期的本地缓存。
 *
 * <h2>status 方法的 phase 推断</h2>
 * {@link #getStatus} 不仅返回 run 状态，还根据最近事件类型推断当前阶段
 * （PLANNING / EXECUTING / EXECUTING_TOOL / SUMMARIZING / PAUSED）。
 * 这是前端进度展示的核心数据源。面试被问"前端怎么知道 agent 正在干什么"，
 * 答案就在 {@link #resolvePhase}。
 *
 * <h2>过期标记</h2>
 * {@link #markExpiredIfNeeded} 在每次读取时检查 run 是否已过期（超过 TTL），
 * 如果是则更新状态并写入 EXPIRED 事件。这种"读时触发写"的模式保证过期状态
 * 即使没有定时任务也能被及时感知。
 *
 * @see LangchainSingleWriterGuard 读写权限守卫
 * @see LangchainRunControlService 写/控制路径（pause/cancel/resume）
 * @see AgentLangchainRunService 写路径入口（createRun）
 */
@Service
@RequiredArgsConstructor
public class LangchainRunReadService {

    private static final Logger log = LoggerFactory.getLogger(LangchainRunReadService.class);

    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final AgentRunStateStore stateStore;
    private final AgentObservabilityService observabilityService;
    private final AgentCreditService creditService;
    private final AgentRunCostService runCostService;
    private final AgentRunCreditQueryService runCreditQueryService;
    private final AgentRunCreditSettlementService creditSettlementService;
    private final AgentModelCatalogService modelCatalogService;
    private final AgentMessageService messageService;
    private final SnapshotPartService snapshotPartService;
    private final LangchainToolCatalogService toolCatalogService;
    private final LangchainSingleWriterGuard singleWriterGuard;
    private final AgentArtifactService artifactService;
    private final ObjectMapper objectMapper;
    private final DataAnalysisObservabilityQuery dataAnalysisObservabilityQuery;
    private final DataAnalysisReadResponseSerializer dataAnalysisSerializer;

    @Value("${agent.run.list.default-days:30}")
    private int listDefaultDays;

    @Value("${agent.artifact.retention-days.normal:7}")
    private int artifactRetentionNormalDays;

    @Value("${agent.artifact.retention-days.admin:30}")
    private int artifactRetentionAdminDays;

    @Value("${agent.api.max-polling-interval-seconds:3}")
    private int maxPollingIntervalSeconds;

    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage getRun(GetAgentRunRequest request) {
        return AgentLangchainRunMessageMapper.toRunMessage(requireReadableRun(request.getId(), request.getUserId()));
    }

    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage updateRun(UpdateAgentRunRequest request) {
        String title = normalizeTitle(request.getTitle());
        if (title == null) {
            throw new IllegalArgumentException("title is required");
        }
        AgentRun run = requireWritableRun(request.getId(), request.getUserId());
        Map<String, Object> ext = readExtMap(run.getExt());
        ext.put("title", title);
        int updated = runMapper.updateExt(run.getId(), run.getUserId(), writeJson(ext));
        if (updated <= 0) {
            throw new IllegalStateException("run not found");
        }
        return AgentLangchainRunMessageMapper.toRunMessage(requireReadableRun(run.getId(), run.getUserId()));
    }

    public ListAgentRunsResponse listRuns(ListAgentRunsRequest request) {
        String userId = requireUserId(request.getUserId());
        int limit = request.getLimit() <= 0 ? 20 : Math.min(request.getLimit(), 100);
        int offset = Math.max(0, request.getOffset());
        AgentRunStatus statusFilter = parseStatusFilter(request.getStatus());
        int days = request.getDays() > 0 ? request.getDays() : listDefaultDays;
        OffsetDateTime fromTime = days > 0 ? OffsetDateTime.now().minusDays(days) : null;

        List<AgentRun> runs = runMapper.listByUser(userId, statusFilter, fromTime, limit, offset);
        int total = runMapper.countByUser(userId, statusFilter, fromTime);
        ListAgentRunsResponse.Builder builder = ListAgentRunsResponse.newBuilder()
                .setTotal(total)
                .setHasMore(offset + runs.size() < total);
        for (AgentRun run : runs) {
            AgentRunStatus effectiveStatus = eventService.shouldMarkExpired(run)
                    ? AgentRunStatus.EXPIRED : run.getStatus();
            builder.addItems(AgentRunListItemMessage.newBuilder()
                    .setId(nvl(run.getId()))
                    .setMessage(nvl(eventService.extractRunDisplayTitle(run.getExt())))
                    .setStatus(effectiveStatus == null ? "" : effectiveStatus.name())
                    .setCreatedAt(run.getStartedAt() == null ? "" : run.getStartedAt().toString())
                    .setCompletedAt(run.getCompletedAt() == null ? "" : run.getCompletedAt().toString())
                    .setHasArtifacts(!artifactService.listArtifacts(run, false).isEmpty())
                    .setDurationMs(nonNegativeLong(run.getDurationMs()))
                    .setTotalTokens(nonNegativeInt(run.getTotalTokens()))
                    .setToolCalls(nonNegativeInt(run.getToolCalls()))
                    .build());
        }
        return builder.build();
    }

    public ListAgentRunEventsResponse listEvents(ListAgentRunEventsRequest request) {
        requireReadableRun(request.getId(), request.getUserId());
        int afterSeq = Math.max(0, request.getAfterSeq());
        int limit = request.getLimit() <= 0 ? 200 : Math.min(request.getLimit(), 500);
        List<AgentRunEvent> events;
        boolean hasMore = false;
        if (request.getLatest()) {
            // snapshot 阶段只需要最近 N 条事件，前端用它补足首屏上下文；
            // 常规补洞仍走 afterSeq，避免每次都传完整事件流。
            events = eventService.listLatestByRunId(request.getId(), limit);
        } else {
            events = eventService.listByRunIdAfterSeq(request.getId(), afterSeq, limit + 1);
            hasMore = events.size() > limit;
            if (hasMore) {
                events = events.subList(0, limit);
            }
        }
        int nextAfterSeq = afterSeq;
        ListAgentRunEventsResponse.Builder builder = ListAgentRunEventsResponse.newBuilder();
        for (AgentRunEvent event : events) {
            builder.addItems(toEventMessage(event));
            if (event.getSeq() != null) {
                nextAfterSeq = Math.max(nextAfterSeq, event.getSeq());
            }
        }
        return builder.setNextAfterSeq(nextAfterSeq).setHasMore(hasMore).build();
    }

    public AgentRunResultMessage getResult(GetAgentRunResultRequest request) {
        AgentRun run = request.getIsAdmin()
                ? requireReadableRunForAdmin(request.getId())
                : requireReadableRun(request.getId(), request.getUserId());
        String snapshotJson = nvl(run.getSnapshotJson());
        String observabilityJson = nvl(observabilityService.loadObservabilityJson(run.getId(), snapshotJson));
        observabilityJson = mergeDataAnalysisResultView(run, observabilityJson);
        Map<String, Object> snapshot = readExtMap(snapshotJson);
        String answerMarkdown = firstNonBlank(stringValue(snapshot.get("answer_markdown")), stringValue(snapshot.get("answer")));
        String structuredAnswerJson = "";
        if (snapshot.get("structured_answer") != null) {
            structuredAnswerJson = writeJson(snapshot.get("structured_answer"));
        }
        int totalCredits = creditService.calculateRunTotalCredits(run, eventService.listByRunId(run.getId()), observabilityJson);
        return AgentRunResultMessage.newBuilder()
                .setId(nvl(run.getId()))
                .setStatus(run.getStatus() == null ? "" : run.getStatus().name())
                .setAnswer(nvl(answerMarkdown))
                .setPayloadJson(snapshotJson)
                .setObservabilityJson(observabilityJson)
                .setTotalCreditsConsumed(totalCredits)
                .setAnswerMarkdown(nvl(answerMarkdown))
                .setStructuredAnswerJson(nvl(structuredAnswerJson))
                .build();
    }

    public world.willfrog.alphafrogmicro.agent.idl.AgentRunCostMessage getRunCost(GetAgentRunCostRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        String observabilityJson = nvl(observabilityService.loadObservabilityJson(run.getId(), run.getSnapshotJson()));
        return runCostService.buildAndPersist(run, observabilityJson);
    }

    public GetAgentRunCreditsResponse getRunCredits(GetAgentRunCreditsRequest request) {
        AgentRun run = request.getIsAdmin()
                ? requireReadableRunForAdmin(request.getId())
                : requireReadableRun(request.getId(), request.getUserId());
        return runCreditQueryService.build(run);
    }

    public GetAgentRunCreditsResponse refreshRunCredits(RefreshAgentRunCreditsRequest request) {
        AgentRun run = request.getIsAdmin()
                ? requireReadableRunForAdmin(request.getId())
                : requireReadableRun(request.getId(), request.getUserId());
        creditSettlementService.refreshCosts(run.getId(), run.getUserId());
        return runCreditQueryService.build(run);
    }

    /**
     * agent 状态轮询 —— 前端/matrix 最高频的读接口。
     *
     * <p>返回当前 run 的轻量状态快照，包含：
     * <ul>
     *   <li>基础状态（COMPLETED/FAILED/EXECUTING 等）</li>
     *   <li>阶段推断（PLANNING/EXECUTING/SUMMARIZING，由 {@link #resolvePhase} 推断）</li>
     *   <li>当前正在执行的 tool 名称（从 TOOL_CALL_STARTED 事件 payload 中提取）</li>
     *   <li>计划进度（planJson + progressJson）</li>
     *   <li>observability 摘要和完整数据可用性标记（不直接返回完整 traces）</li>
     *   <li>credits 消耗</li>
     *   <li>已用时长（elapsedMs）</li>
     * </ul>
     *
     * <p>这是 agent 前端展示的核心数据源。matrix 脚本在 poll 循环中每 3 秒调一次。
     * 这里刻意不返回 full observability，避免 status poll 因大 trace 变成 MB 级响应；
     * 需要完整观测或安全调用详情时，由结果/详情接口按需加载。
     */
    public AgentRunStatusMessage getStatus(GetAgentRunStatusRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        AgentRunEvent latestEvent = eventService.findLatestByRunId(run.getId());
        String planJson = nvl(run.getPlanJson());
        var cachedPlan = stateStore.loadPlan(run.getId());
        if (cachedPlan.isPresent()) {
            // 执行中 run 的 Redis plan 更新更及时；DB plan 用于历史和 Redis 过期后的读取。
            // status 接口优先 Redis，是为了前端轮询时看到最新 HITL/plan override 状态。
            planJson = cachedPlan.get();
        }
        String progressJson = planJson.isBlank() ? "" : stateStore.buildProgressJson(run.getId(), planJson);
        // status 是高频轮询接口，只返回 summary，不拉完整 observability。
        // 完整 trace 可能很大，应该由详情页或 matrix 按需读取。
        String observabilitySummaryJson = observabilityService.loadObservabilitySummaryJson(run.getId(), run.getSnapshotJson());
        observabilitySummaryJson = mergeDataAnalysisStatusView(run, observabilitySummaryJson);
        boolean observabilityFullAvailable = observabilityService.isFullObservabilityAvailable(run.getId(), run.getSnapshotJson());
        int totalCredits = creditService.calculateRunTotalCredits(run, eventService.listByRunId(run.getId()), observabilitySummaryJson);
        Integer maxSeq = eventService.findMaxSeq(run.getId());
        return toStatusMessage(
                run,
                latestEvent,
                planJson,
                progressJson,
                "",
                observabilitySummaryJson,
                observabilityFullAvailable,
                totalCredits,
                maxSeq == null ? 0 : maxSeq,
                toEpochMillis(run.getStartedAt()),
                toEpochMillis(run.getCompletedAt()),
                computeElapsedMs(run, System.currentTimeMillis()));
    }

    public ListAgentToolsResponse listTools(ListAgentToolsRequest request) {
        requireUserId(request.getUserId());
        return ListAgentToolsResponse.newBuilder()
                .addAllItems(toolCatalogService.listToolMessages())
                .build();
    }

    public GetAgentConfigResponse getConfig(GetAgentConfigRequest request) {
        requireUserId(request.getUserId());
        return GetAgentConfigResponse.newBuilder()
                .setRetentionDays(AgentRetentionConfigMessage.newBuilder()
                        .setNormalDays(Math.max(0, artifactRetentionNormalDays))
                        .setAdminDays(Math.max(0, artifactRetentionAdminDays))
                        .build())
                .setMaxPollingInterval(Math.max(1, maxPollingIntervalSeconds))
                .setFeatures(AgentFeatureConfigMessage.newBuilder()
                        .setParallelExecution(true)
                        .setPauseResume(true)
                        .build())
                .build();
    }

    public ListAgentModelsResponse listModels(ListAgentModelsRequest request) {
        requireUserId(request.getUserId());
        ListAgentModelsResponse.Builder builder = ListAgentModelsResponse.newBuilder();
        for (AgentModelCatalogService.ModelCatalogItem item : modelCatalogService.listModels()) {
            builder.addModels(AgentModelMessage.newBuilder()
                    .setId(nvl(item.id()))
                    .setDisplayName(nvl(item.displayName()))
                    .setEndpoint(nvl(item.endpoint()))
                    .setCompositeId(nvl(item.compositeId()))
                    .setBaseRate(item.baseRate())
                    .addAllFeatures(item.features() == null ? List.of() : item.features())
                    .addAllValidProviders(item.validProviders() == null ? List.of() : item.validProviders())
                    .build());
        }
        return builder.build();
    }

    public GetAgentCreditsResponse getCredits(GetAgentCreditsRequest request) {
        AgentCreditService.CreditSummary summary = creditService.getUserCredits(request.getUserId());
        return GetAgentCreditsResponse.newBuilder()
                .setTotalCredits(summary.totalCredits())
                .setRemainingCredits(summary.remainingCredits())
                .setUsedCredits(summary.usedCredits())
                .setResetCycle(nvl(summary.resetCycle()))
                .setNextResetAt(nvl(summary.nextResetAt()))
                .build();
    }

    public ApplyAgentCreditsResponse applyCredits(ApplyAgentCreditsRequest request) {
        AgentCreditService.ApplyCreditSummary summary = creditService.applyCredits(
                request.getUserId(),
                request.getAmount(),
                request.getReason(),
                request.getContact());
        return ApplyAgentCreditsResponse.newBuilder()
                .setApplicationId(nvl(summary.applicationId()))
                .setTotalCredits(summary.totalCredits())
                .setRemainingCredits(summary.remainingCredits())
                .setUsedCredits(summary.usedCredits())
                .setStatus(nvl(summary.status()))
                .setAppliedAt(nvl(summary.appliedAt()))
                .build();
    }

    public AgentEmpty submitFeedback(SubmitAgentFeedbackRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        eventService.append(run.getId(), run.getUserId(), "FEEDBACK_RECEIVED", Map.of(
                "rating", request.getRating(),
                "comment", request.getComment(),
                "tags_json", request.getTagsJson(),
                "payload_json", request.getPayloadJson()));
        return AgentEmpty.newBuilder().build();
    }

    public ExportAgentRunResponse exportRun(ExportAgentRunRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        String exportId = java.util.UUID.randomUUID().toString().replace("-", "");
        eventService.append(run.getId(), run.getUserId(), "EXPORT_REQUESTED", Map.of(
                "export_id", exportId,
                "format", request.getFormat()));
        return ExportAgentRunResponse.newBuilder()
                .setExportId(exportId)
                .setStatus("not_implemented")
                .setMessage("export not implemented in langchain service yet")
                .build();
    }

    public ListAgentMessagesResponse listMessages(ListAgentMessagesRequest request) {
        String userId = requireUserId(request.getUserId());
        String runId = requireId(request.getRunId(), "run_id");
        requireReadableRun(runId, userId);
        int limit = request.getLimit() <= 0 ? 50 : Math.min(request.getLimit(), 200);
        int offset = Math.max(0, request.getOffset());
        boolean includeInitial = request.getIncludeInitial();
        int total = includeInitial
                ? messageService.countMessages(runId)
                : messageService.countMessagesExcludingInitial(runId);
        List<AgentRunMessage> messages = includeInitial
                ? messageService.listMessagesWithPagination(runId, limit, offset)
                : messageService.listMessagesWithPaginationExcludingInitial(runId, limit, offset);
        ListAgentMessagesResponse.Builder builder = ListAgentMessagesResponse.newBuilder()
                .setTotal(total)
                .setHasMore(offset + messages.size() < total);
        for (AgentRunMessage msg : messages) {
            builder.addItems(AgentRunMessageItem.newBuilder()
                    .setId(msg.getId() == null ? 0L : msg.getId())
                    .setSeq(msg.getSeq() == null ? 0 : msg.getSeq())
                    .setRole(nvl(msg.getRole()))
                    .setContent(nvl(msg.getContent()))
                    .setMsgType(nvl(msg.getMsgType()))
                    .setMetaJson(nvl(msg.getMetaJson()))
                    .setCreatedAt(msg.getCreatedAt() == null ? "" : msg.getCreatedAt().toString())
                    .build());
        }
        return builder.build();
    }

    public AgentSnapshotPartsMetaMessage getSnapshotPartsMeta(GetAgentSnapshotPartsRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        // 大 snapshot 不直接塞进单个响应。先生成 meta，让前端知道分片数量、压缩方式和校验信息。
        SnapshotPartsMeta meta = snapshotPartService.getOrBuildMeta(
                run.getId(),
                run.getSnapshotJson(),
                request.getMaxPartSize());
        return AgentSnapshotPartsMetaMessage.newBuilder()
                .setRunId(nvl(meta.getRunId()))
                .setPartSize(meta.getPartSize())
                .setTotalParts(meta.getTotalParts())
                .setUncompressedSize(meta.getUncompressedSize())
                .setCompressedSize(meta.getCompressedSize())
                .setCompression(nvl(meta.getCompression()))
                .setChecksum(nvl(meta.getChecksum()))
                .build();
    }

    public AgentSnapshotPartMessage getSnapshotPart(GetAgentSnapshotPartRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        // part 内容按 index 拉取，避免超大 run 详情超过网关/浏览器单次响应上限。
        // meta 和 part 都通过同一个 SnapshotPartService 生成，保证分片参数一致。
        SnapshotPartsMeta meta = snapshotPartService.getOrBuildMeta(
                run.getId(),
                run.getSnapshotJson(),
                request.getMaxPartSize());
        byte[] content = snapshotPartService.getPartBytes(
                run.getId(),
                run.getSnapshotJson(),
                request.getPartIndex(),
                request.getMaxPartSize());
        return AgentSnapshotPartMessage.newBuilder()
                .setRunId(nvl(meta.getRunId()))
                .setPartIndex(request.getPartIndex())
                .setPartSize(meta.getPartSize())
                .setTotalParts(meta.getTotalParts())
                .setContent(ByteString.copyFrom(content))
                .setCompression(nvl(meta.getCompression()))
                .build();
    }

    AgentRun requireReadableRun(String id, String userId) {
        return singleWriterGuard.requireReadable(requireRun(id, userId));
    }

    AgentRun requireReadableRunForAdmin(String id) {
        return singleWriterGuard.requireReadable(requireRunForAdmin(id));
    }

    AgentRun requireWritableRun(String id, String userId) {
        return singleWriterGuard.requireWritable(requireRun(id, userId));
    }

    private AgentRun requireRun(String id, String userId) {
        String safeId = requireId(id, "id");
        String safeUserId = requireUserId(userId);
        AgentRun run = runMapper.findByIdAndUser(safeId, safeUserId);
        if (run == null) {
            throw new IllegalArgumentException("run not found");
        }
        return markExpiredIfNeeded(run);
    }

    private AgentRun requireRunForAdmin(String id) {
        String safeId = requireId(id, "id");
        AgentRun run = runMapper.findById(safeId);
        if (run == null) {
            throw new IllegalArgumentException("run not found");
        }
        return markExpiredIfNeeded(run);
    }

    private AgentRun markExpiredIfNeeded(AgentRun run) {
        if (run == null || !eventService.shouldMarkExpired(run)) {
            return run;
        }
        // 过期是读时发现并补写的状态：旧 run 没有后台定时器一直扫描。
        // 一旦某次读取发现超出保留窗口，就补 RUN_EXPIRED 事件并刷新 Redis 状态。
        runMapper.updateStatus(run.getId(), run.getUserId(), AgentRunStatus.EXPIRED);
        eventService.append(run.getId(), run.getUserId(), "RUN_EXPIRED", Map.of(
                "run_id", run.getId(),
                "expired_at", OffsetDateTime.now().toString()));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.EXPIRED.name());
        AgentRun refreshed = runMapper.findByIdAndUser(run.getId(), run.getUserId());
        return refreshed == null ? run : refreshed;
    }

    private String requireUserId(String userId) {
        return requireId(userId, "user_id");
    }

    private String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private AgentRunStatus parseStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AgentRunStatus.valueOf(status.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid status filter: " + status);
        }
    }

    private AgentRunEventMessage toEventMessage(AgentRunEvent event) {
        return AgentRunEventMessage.newBuilder()
                .setId(event.getId() == null ? 0L : event.getId())
                .setRunId(nvl(event.getRunId()))
                .setSeq(event.getSeq() == null ? 0 : event.getSeq())
                .setEventType(nvl(event.getEventType()))
                .setPayloadJson(nvl(event.getPayloadJson()))
                .setCreatedAt(event.getCreatedAt() == null ? "" : event.getCreatedAt().toString())
                .build();
    }

    private AgentRunStatusMessage toStatusMessage(AgentRun run,
                                                  AgentRunEvent lastEvent,
                                                  String planJson,
                                                  String progressJson,
                                                  String observabilityJson,
                                                  String observabilitySummaryJson,
                                                  boolean observabilityFullAvailable,
                                                  int totalCreditsConsumed,
                                                  int eventCount,
                                                  long startedAtMs,
                                                  long completedAtMs,
                                                  long elapsedMs) {
        String lastEventType = lastEvent == null ? "" : nvl(lastEvent.getEventType());
        return AgentRunStatusMessage.newBuilder()
                .setId(nvl(run.getId()))
                .setStatus(run.getStatus() == null ? "" : run.getStatus().name())
                .setPhase(resolvePhase(run.getStatus(), lastEventType))
                .setCurrentTool(resolveCurrentTool(lastEventType, lastEvent == null ? null : lastEvent.getPayloadJson()))
                .setLastEventType(lastEventType)
                .setLastEventAt(lastEvent == null || lastEvent.getCreatedAt() == null ? "" : lastEvent.getCreatedAt().toString())
                .setLastEventPayloadJson(lastEvent == null ? "" : nvl(lastEvent.getPayloadJson()))
                .setPlanJson(nvl(planJson))
                .setProgressJson(nvl(progressJson))
                .setObservabilityJson(nvl(observabilityJson))
                .setObservabilitySummaryJson(nvl(observabilitySummaryJson))
                .setObservabilityFullAvailable(observabilityFullAvailable)
                .setTotalCreditsConsumed(Math.max(0, totalCreditsConsumed))
                .setEventCount(eventCount)
                .setStartedAtMs(startedAtMs)
                .setCompletedAtMs(completedAtMs)
                .setElapsedMs(elapsedMs)
                .build();
    }

    /**
     * 根据 run 状态和最近事件类型推断当前阶段，用于前端进度展示。
     *
     * <p>为什么不只用 status？因为 EXECUTING 状态涵盖多种子阶段
     * （planning 结束但还没开始执行、正在执行 tool、正在写 final answer 等），
     * 只靠 status 无法区分。配合最近事件类型可以更精确推断。
     *
     * <p>推断优先级：终态 ＞ WAITING（PAUSED） ＞ 事件推断 ＞ status fallback。
     */
    private String resolvePhase(AgentRunStatus status, String lastEventType) {
        if (status == null) {
            return "";
        }
        if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELED || status == AgentRunStatus.EXPIRED) {
            return status.name();
        }
        if (status == AgentRunStatus.WAITING) {
            return "PAUSED";
        }
        if (status == AgentRunStatus.WAITING_TOOL_JOB) {
            // 直接暴露持久阶段，避免把“已释放 worker、等待外部结果”误显示成普通 PAUSED 或 EXECUTING。
            // 这里的 phase 只用于读取/UI，不参与 resume 决策；真正的重入资格仍来自 anchor 的
            // READY/LAUNCHING、resumeToken 与 leaseVersion，客户端刷新不会触发任何执行副作用。
            return "WAITING_TOOL_JOB";
        }
        if ("PLAN_READY".equals(lastEventType)
                || "PLANNING_STARTED".equals(lastEventType)
                || "TODO_LIST_CREATED".equals(lastEventType)) {
            // PLAN_READY 已经有计划，但还没有 TODO_NODE_STARTED/TOOL_CALL_STARTED，
            // 对前端来说仍应展示为规划阶段结束、执行尚未正式展开。
            return "PLANNING";
        }
        if ("FINAL_ANSWER_GENERATING".equals(lastEventType) || "SUMMARIZING_STARTED".equals(lastEventType)) {
            return "SUMMARIZING";
        }
        if ("TOOL_CALL_STARTED".equals(lastEventType)) {
            // 当前工具名从 payload 中解析，phase 只负责告诉前端这是工具执行中。
            return "EXECUTING_TOOL";
        }
        if ("EXECUTION_STARTED".equals(lastEventType) || "TODO_STARTED".equals(lastEventType)
                || "TODO_FINISHED".equals(lastEventType) || "WORKFLOW_RESUMED".equals(lastEventType)) {
            return "EXECUTING";
        }
        return status.name();
    }

    private String resolveCurrentTool(String lastEventType, String payloadJson) {
        if (!"TOOL_CALL_STARTED".equals(lastEventType) || payloadJson == null || payloadJson.isBlank()) {
            return "";
        }
        Map<String, Object> payload = readExtMap(payloadJson);
        return firstNonBlank(stringValue(payload.get("tool_name")), stringValue(payload.get("tool")));
    }

    private Map<String, Object> readExtMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<?, ?> raw = objectMapper.readValue(json, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String normalized = title.trim();
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > 120) {
            throw new IllegalArgumentException("title too long");
        }
        return normalized;
    }

    private long toEpochMillis(OffsetDateTime time) {
        return time == null ? 0L : time.toInstant().toEpochMilli();
    }

    private long computeElapsedMs(AgentRun run, long nowMs) {
        if (run.getStartedAt() == null) {
            return 0L;
        }
        long startMs = run.getStartedAt().toInstant().toEpochMilli();
        if (run.getCompletedAt() != null) {
            return Math.max(0L, run.getCompletedAt().toInstant().toEpochMilli() - startMs);
        }
        return Math.max(0L, nowMs - startMs);
    }

    private int nonNegativeInt(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private long nonNegativeLong(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String mergeDataAnalysisStatusView(AgentRun run, String existingJson) {
        String runId = run.getId();
        try {
            String dataAnalysisJson = dataAnalysisSerializer.serializeStatusFromSummary(
                    runId,
                    dataAnalysisObservabilityQuery.findSummaryByRunId(
                            runId, dataAnalysisReadMode(run.getStatus())));
            if (dataAnalysisJson.equals("{}")) {
                return existingJson;
            }
            return mergeJsonObjects(runId, "status", existingJson, dataAnalysisJson);
        } catch (Exception e) {
            log.warn("合并 data-analysis status 视图失败 runId={} 异常={}/{}",
                    runId, e.getClass().getSimpleName(), e.getMessage());
            return existingJson;
        }
    }

    private String mergeDataAnalysisResultView(AgentRun run, String existingJson) {
        String runId = run.getId();
        try {
            String dataAnalysisJson = dataAnalysisSerializer.serializeResultView(
                    dataAnalysisObservabilityQuery.findByRunId(
                            runId, dataAnalysisReadMode(run.getStatus())));
            if (dataAnalysisJson.equals("{}")) {
                return existingJson;
            }
            return mergeJsonObjects(runId, "result", existingJson, dataAnalysisJson);
        } catch (Exception e) {
            log.warn("合并 data-analysis result 视图失败 runId={} 异常={}/{}",
                    runId, e.getClass().getSimpleName(), e.getMessage());
            return existingJson;
        }
    }

    private DataAnalysisObservabilityReadMode dataAnalysisReadMode(AgentRunStatus status) {
        if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED) {
            return DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY;
        }
        return DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST;
    }

    private String mergeJsonObjects(String runId, String view, String baseJson, String overlayJson) {
        if (baseJson == null || baseJson.isBlank()) {
            return overlayJson;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode baseNode = objectMapper.readTree(baseJson);
            if (!baseNode.isObject()) {
                log.warn("base JSON 非对象 runId={} view={}，保留原始响应", runId, view);
                return baseJson;
            }
            com.fasterxml.jackson.databind.JsonNode overlayNode = objectMapper.readTree(overlayJson);
            if (!overlayNode.isObject()) {
                log.warn("overlay JSON 非对象 runId={} view={}，保留原始响应", runId, view);
                return baseJson;
            }
            Map<String, Object> base = objectMapper.convertValue(baseNode, Map.class);
            Map<String, Object> overlay = objectMapper.convertValue(overlayNode, Map.class);
            base.putAll(overlay);
            return objectMapper.writeValueAsString(base);
        } catch (Exception e) {
            log.warn("JSON merge 失败 runId={} view={} 异常={}/{}，保留原始响应",
                    runId, view, e.getClass().getSimpleName(), e.getMessage());
            return baseJson;
        }
    }
}
