package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.internal.OpenAiUtils;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.exception.ProviderChatException;
import world.willfrog.agent.platform.exception.ProviderFailureCategory;
import world.willfrog.agent.platform.exception.RunBudgetException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenRouter Provider 路由的 ChatModel 实现 (ALP-25)。
 *
 * <p>这个类是 agentLangchainService 真正发起模型请求的位置。上一层
 * {@link AgentAiServiceFactory} 只负责按阶段构造 ChatModel，到了这里才会把
 * LangChain4j 的 {@link ChatRequest} 转成 OpenAI 兼容的 chat completions HTTP 请求。
 * 模型请求里到底带了什么、为什么 OpenRouter 会走某个 provider、观测里的 llm trace
 * 从哪里来，答案都在这个文件。讲解材料见
 * {@code agent-working-docs/code-review/phase2/agent-run-overall/interview-comments-migrated.md}。</p>
 *
 * <p>与普通 SDK 封装不同，本类刻意没有直接依赖某个现成 OpenAI client，而是手写
 * HTTP 请求和 SSE 聚合。原因是 agent 运行需要额外控制 provider order、结构化输出、
 * raw HTTP 捕获、streaming progress 和预算检查；这些都必须和 {@link AgentContext}
 * 中的 runId / phase / stage 绑定。</p>
 *
 * <p>本类支持：</p>
 * <ol>
 *   <li><b>Provider 优先级路由</b>：通过 providerOrder 指定优先使用的 Provider</li>
 *   <li><b>原始 HTTP 捕获</b>：完整记录请求/响应信息</li>
 *   <li><b>可观测性上报</b>：将 HTTP 观测数据上报到 AgentObservabilityService</li>
 *   <li><b>默认流式输出</b>：对 LLM Provider 使用 stream=true，内部聚合 SSE 流</li>
 *   <li><b>实时事件契约</b>：为每次逻辑调用生成 {@code llm_call_id}，并在
 *       {@code LLM_CALL_STARTED/DELTA/FINISHED} 中带上 todo/workflow/stage 归属</li>
 * </ol>
 *
 * <p>需要特别注意 OpenRouter 的 fallback 语义：只要请求体没有显式写
 * {@code allow_fallbacks=false}，OpenRouter 可能在指定 provider 不可用时切到其它 provider。
 * 这会导致账单侧或第三方 provider 后台出现「明明配置了 A，实际走了 B」的现象。
 * 所以下方构造 provider 字段时始终显式禁止 fallback，把 provider 选择权留在配置层。</p>
 * 
 * @see AgentAiServiceFactory
 * @see RawHttpLogger
 * @see AgentObservabilityService
 * @since ALP-25
 */
@RequiredArgsConstructor
@Slf4j
public class OpenRouterProviderRoutedChatModel implements ChatModel {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(45))
            .build();
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofSeconds(25);

    // ========== 核心依赖 ==========

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final Map<String, String> customHeaders;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final List<String> providerOrder;
    
    // ALP-25 新增：HTTP 记录和观测
    private final RawHttpLogger httpLogger;
    private final AgentObservabilityService observabilityService;
    private final OpenRouterCostService openRouterCostService;
    private final AgentEventService eventService;
    private final String endpointName;

    // Debug 配置加载器（热加载）
    private final AgentLlmLocalConfigLoader localConfigLoader;

    // LLM latency window for adaptive concurrency (shared across all calls)
    private final LangchainLlmLatencyWindow latencyWindow;

    @Setter
    private AgentRunBudgetService budgetService;

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        /*
         * doChat 是一次逻辑 LLM 调用的完整边界。这里的「一次」对应 observability
         * 里的 llmCallCount，而不是底层 HTTP attempt：sendWithRetry 可能在 5xx/429
         * 场景下对同一个逻辑调用重试多次，重试明细会写入 raw HTTP attempts。
         *
         * 处理顺序故意保持为：
         *   1. 预算检查；
         *   2. 从 ChatRequest 构造 provider-specific request body；
         *   3. 发送流式 HTTP 并聚合 SSE；
         *   4. 解析 token usage / finish reason；
         *   5. 写入轻量 llmCalls 统计与可选 raw HTTP trace。
         *
         * 这样做的好处是：即使 raw HTTP capture 没开，预算和 llmCalls 统计仍然完整；
         * raw body 只作为排障增强能力，不参与主流程正确性。
         */
        List<ChatMessage> messages = chatRequest.messages();
        List<ToolSpecification> toolSpecifications = chatRequest.toolSpecifications();
        String requestJson = null;
        long requestStartedAt = System.currentTimeMillis();
        
        // ALP-25：判断是否记录 HTTP（客户端参数 + 服务端白名单）
        boolean clientWantsCapture = observabilityService != null 
                && observabilityService.isCaptureLlmRequestsEnabled(AgentContext.getRunId());
        boolean endpointAllowed = httpLogger != null && httpLogger.shouldCapture(endpointName);
        boolean shouldCapture = clientWantsCapture && endpointAllowed;
        RawHttpLogger.HttpRequestRecord requestRecord = null;
        RawHttpLogger.HttpResponseRecord responseRecord = null;
        String curlCommand = null;
        int statusCode = -1;
        String responseJson = null;
        List<Map<String, Object>> attempts = List.of();
        
        // 这个 id 是前端看到的 llm_call_id，同时也是 observability traceId override。
        // 保持二者一致后，UI 可以从 SSE 卡片懒加载 safe detail，而不需要再猜 provider generation_id。
        String llmTraceId = java.util.UUID.randomUUID().toString().replace("-", "");
        try {
            if (budgetService != null) {
                budgetService.checkBeforeLlmCall();
            }
            emitLlmCallStarted(llmTraceId, true);
            // ========== 1. 构建请求 ==========
            ChatCompletionRequest.Builder builder = ChatCompletionRequest.builder()
                    .model(OpenAiCompatibleChatModelSupport.nvl(modelName))
                    .messages(OpenAiUtils.toOpenAiMessages(messages == null ? List.of() : messages, true, "reasoning_content"))
                    .temperature(temperature)
                    .maxCompletionTokens(maxTokens);
            
            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                builder.tools(OpenAiUtils.toTools(toolSpecifications, false));
            }
            
            ChatCompletionRequest request = builder.build();
            Map<String, Object> requestJsonMap = objectMapper.convertValue(
                    request,
                    new TypeReference<Map<String, Object>>() {}
            );
            // 默认启用流式输出。SSE 聚合器负责还原 content/reasoning/tool_calls。
            requestJsonMap.put("stream", true);
            applyStreamingOptions(requestJsonMap, baseUrl, AgentContext.getPhase());
            applyEndpointSamplingDefaults(requestJsonMap, baseUrl);

            // OpenRouter 特有：添加 providerOrder 与结构化输出参数。
            //
            // StructuredOutputSpec 由 planning 阶段通过 AgentContext 注入，最终会落到
            // OpenRouter 的 response_format=json_schema。Provider 字段必须与它一起处理：
            // - order 限定 provider 优先级；
            // - allow_fallbacks=false 阻止 OpenRouter 静默切到列表外 provider；
            // - require_parameters=true 时，OpenRouter 会过滤不支持 response_format/tools
            //   等字段的 provider，因此字段名兼容性会直接影响是否 404。
            AgentContext.StructuredOutputSpec structuredOutputSpec = AgentContext.getStructuredOutputSpec();
            if (isOpenRouterEndpoint(baseUrl)) {
                normalizeOpenRouterTokenLimit(requestJsonMap);
                Map<String, Object> provider = new LinkedHashMap<>();
                provider.put("order", providerOrder == null ? List.of() : providerOrder);
                // 始终禁止 OpenRouter 自动 fallback 到其它 provider
                provider.put("allow_fallbacks", false);
                if (structuredOutputSpec != null) {
                    requestJsonMap.put("response_format", structuredOutputSpec.asResponseFormat());
                    provider.put("require_parameters", structuredOutputSpec.requireProviderParameters());
                }
                requestJsonMap.put("provider", provider);

                // 添加 OpenRouter reasoning (thinking) 配置
                String reasoningEffort = AgentContext.getReasoningEffort();
                if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                    Map<String, Object> reasoning = new LinkedHashMap<>();
                    reasoning.put("effort", reasoningEffort);
                    requestJsonMap.put("reasoning", reasoning);
                }
            } else if (isFireworksEndpoint(baseUrl)) {
                String reasoningEffort = AgentContext.getReasoningEffort();
                applyFireworksReasoningEffort(requestJsonMap, reasoningEffort);
            } else if (structuredOutputSpec != null) {
                requestJsonMap.put("response_format", structuredOutputSpec.asResponseFormat());
            }

            requestJson = objectMapper.writeValueAsString(requestJsonMap);
            if (log.isDebugEnabled()) {
                log.debug("OpenRouter provider routing enabled: providers={}, structuredSchema={}",
                        providerOrder,
                        structuredOutputSpec == null ? "" : structuredOutputSpec.schemaName());
            }
            
            // 构建 HTTP 请求信息
            String requestUrl = OpenAiCompatibleChatModelSupport.buildChatCompletionsUrl(baseUrl);
            Map<String, String> requestHeaders = OpenAiCompatibleChatModelSupport.buildRequestHeaders(apiKey);
            
            // 确保 requestHeaders 包含所有实际发送的 headers
            requestHeaders.put("Content-Type", "application/json");
            requestHeaders.put("Accept", "application/json");
            
            Duration requestTimeout = resolveRequestTimeout();
            HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + OpenAiCompatibleChatModelSupport.nvl(apiKey))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));
            
            // 添加自定义 headers
            if (customHeaders != null && !customHeaders.isEmpty()) {
                for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        httpRequestBuilder.header(entry.getKey(), entry.getValue());
                        requestHeaders.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            
            // ALP-25：记录 HTTP 请求
            if (shouldCapture) {
                requestRecord = httpLogger.recordRequest(requestUrl, "POST", requestHeaders, requestJson);
            }
            
            // Debug curl 日志（热加载 debug.logLlmCurl）— 临时关闭：整包 request body 打 INFO 会撑爆远程 tmux 日志
            // if (isDebugCurlEnabled()) {
            //     curlCommand = buildCurlCommand(requestUrl, requestHeaders, requestJson);
            //     log.info("[LLM Debug CURL] endpoint={} model={} providerOrder={}\n{}",
            //             endpointName, modelName, providerOrder, curlCommand);
            // }
            
            // ========== 2. 发送 HTTP 请求（流式，按 logical call 聚合重试） ==========
            //
            // 这里的 retry 是 provider HTTP 层面的恢复，不等同于 agent 的语义重试：
            // agent 语义重试会重新让模型思考，而 HTTP retry 只是同一份请求体重发。
            // 因此 retry 次数由 run budget 单独限制，避免一个 LLM step 因 provider 抖动
            // 消耗过多 wall-clock。
            LlmRequestRetryPolicy retryPolicy = resolveRetryPolicy();
            AttemptResult attemptResult = sendWithRetry(requestJson, requestUrl, apiKey, customHeaders,
                    shouldCapture, requestStartedAt, retryPolicy, requestTimeout, llmTraceId);
            attempts = attemptResult.attempts();
            HttpResponse<java.io.InputStream> httpResponse = attemptResult.response();
            responseRecord = attemptResult.responseRecord();
            statusCode = attemptResult.statusCode();
            responseJson = attemptResult.errorBody();
            long durationMs = System.currentTimeMillis() - requestStartedAt;
            
            ChatCompletionResponse completion;
            String reasoningContent = null;
            StreamingProgressTracker.StreamingProgressSnapshot progressSnapshot = null;
            OpenAiCompatibleChatModelSupport.SseAggregateResult aggregateResult = null;

            if (statusCode >= 200 && statusCode < 300) {
                if (attemptResult.streamError() != null || attemptResult.aggregateResult() == null) {
                    Exception streamError = attemptResult.streamError();
                    String streamErrorMessage = streamError == null
                            ? "SSE stream aggregation returned no result"
                            : streamError.getMessage();
                    ProviderFailureCategory category = classifyProviderError(
                            statusCode, streamErrorMessage, streamError, providerOrder);
                    throw ProviderChatException.of(
                            statusCode,
                            extractErrorCodeFromBody(streamErrorMessage),
                            providerOrder,
                            modelName,
                            endpointName,
                            streamErrorMessage,
                            category,
                            streamError
                    );
                }
                durationMs = System.currentTimeMillis() - requestStartedAt;
                latencyWindow.record(durationMs);
                progressSnapshot = attemptResult.streamingProgress();
                aggregateResult = attemptResult.aggregateResult();
                completion = aggregateResult.completionResponse();
                reasoningContent = aggregateResult.reasoningContent();

                // 为了 HTTP 捕获，将聚合后的响应体序列化
                Map<String, Object> aggregatedPayload = objectMapper.convertValue(completion,
                        new TypeReference<Map<String, Object>>() {
                        });
                if (aggregateResult.lastId() != null && !aggregateResult.lastId().isBlank()) {
                    aggregatedPayload.put("id", aggregateResult.lastId());
                }
                if (aggregateResult.lastUsage() != null && !aggregateResult.lastUsage().isEmpty()) {
                    aggregatedPayload.put("usage", aggregateResult.lastUsage());
                }
                String aggregatedBody = objectMapper.writeValueAsString(aggregatedPayload);
                Map<String, String> responseHeaders = new java.util.HashMap<>();
                responseHeaders.put("Content-Type", "application/json");
                if (shouldCapture) {
                    responseRecord = httpLogger.recordResponse(statusCode, responseHeaders, aggregatedBody, durationMs);
                    curlCommand = httpLogger.toCurlCommand(requestRecord);
                } else {
                    // 计费元数据不属于 raw diagnostics：即使不开 raw capture，也要让
                    // observability 从响应体中解析 usage.cost 与 generation id。
                    responseRecord = RawHttpLogger.HttpResponseRecord.builder()
                            .statusCode(statusCode)
                            .headers(Map.of())
                            .body(aggregatedBody)
                            .durationMs(durationMs)
                            .timestamp(System.currentTimeMillis())
                            .build();
                }
            } else {
                ProviderFailureCategory category = classifyProviderError(
                        statusCode, responseJson, null, providerOrder);
                String errorCode = extractErrorCodeFromBody(responseJson);
                String logDetail = "OpenRouter provider routed chat completion failed"
                        + " (http=" + statusCode
                        + ", providers=" + providerOrder
                        + ", model=" + OpenAiCompatibleChatModelSupport.nvl(modelName)
                        + ", error=" + OpenAiCompatibleChatModelSupport.shorten(responseJson)
                        + ", request=" + OpenAiCompatibleChatModelSupport.shorten(requestJson) + ")";
                log.warn(logDetail);
                throw ProviderChatException.of(
                        statusCode,
                        errorCode,
                        providerOrder,
                        modelName,
                        endpointName,
                        responseJson,
                        category,
                        null
                );
            }
            
            // 解析响应体
            AiMessage aiMessage = OpenAiUtils.aiMessageFrom(completion, true);
            TokenUsage tokenUsage = OpenAiUtils.tokenUsageFrom(completion.usage());
            FinishReason finishReason = OpenAiCompatibleChatModelSupport.extractFinishReason(completion);

            // 保存 thinking 内容和进度。
            // reasoningContent 来自 SSE 聚合，同时回写 AiMessage.thinking()
            // 以防合成的 completion 未带 reasoning_content 时 toOpenAiMessages 丢字段。
            if (reasoningContent != null && !reasoningContent.isBlank()) {
                AgentContext.setThinkingContent(reasoningContent);
                if (aiMessage.thinking() == null || aiMessage.thinking().isBlank()) {
                    aiMessage = AiMessage.builder()
                            .text(aiMessage.text())
                            .thinking(reasoningContent)
                            .toolExecutionRequests(aiMessage.toolExecutionRequests())
                            .build();
                }
            }
            if (progressSnapshot != null) {
                AgentContext.setStreamingProgress(progressSnapshot);
            }
            
            // 上报成功观测：minimal billing trace 始终记录，raw HTTP/detail 仍由 capture 开关控制。
            String observabilityTraceId = null;
            if (observabilityService != null) {
                observabilityTraceId = reportLlmCall(llmTraceId, requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, null,
                        reasoningContent, progressSnapshot, attemptResult.attempts());
            }

            // SSE live event: LLM call finished (success)
            Double actualCost = extractActualCostFromUsage(aggregateResult.lastUsage());
            String generationId = aggregateResult.lastId();
            emitLlmCallFinished(llmTraceId, tokenUsage, durationMs, actualCost, generationId, null,
                    observabilityTraceId, attemptResult.attempts());

            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(tokenUsage)
                            .finishReason(finishReason)
                            .build())
                    .build();
            
        } catch (ProviderChatException | RunBudgetException e) {
            // SSE live event: LLM call finished (typed error)
            long durationMs = System.currentTimeMillis() - requestStartedAt;
            String errorType = e.getClass().getSimpleName();
            emitLlmCallFinished(llmTraceId, null, durationMs, null, null, errorType + ": " + e.getMessage(), null, attempts);

            // ALP-25：上报 typed 异常
            if (shouldCapture && observabilityService != null) {
                reportLlmCall(llmTraceId, requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs,
                        errorType + ": " + e.getMessage(), null, null, attempts);
            }

            throw e;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            // SSE live event: LLM call finished (interrupted)
            long durationMs = System.currentTimeMillis() - requestStartedAt;
            emitLlmCallFinished(llmTraceId, null, durationMs, null, null, "INTERRUPTED", null, attempts);

            // ALP-25：上报中断错误
            if (shouldCapture && observabilityService != null) {
                reportLlmCall(llmTraceId, requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, "INTERRUPTED",
                        null, null, List.of());
            }

            String detail = "OpenRouter provider routed chat completion interrupted"
                    + " (providers=" + providerOrder
                    + ", model=" + OpenAiCompatibleChatModelSupport.nvl(modelName) + ")";
            throw new IllegalStateException(detail, e);
            
        } catch (Exception e) {
            // SSE live event: LLM call finished (error)
            long durationMs = System.currentTimeMillis() - requestStartedAt;
            String errorType = e.getClass().getSimpleName();
            emitLlmCallFinished(llmTraceId, null, durationMs, null, null, errorType + ": " + e.getMessage(), null, attempts);

            // ALP-25：上报异常
            if (shouldCapture && observabilityService != null) {
                reportLlmCall(llmTraceId, requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs,
                            errorType + ": " + e.getMessage(), null, null, attempts);
            }

            String detail = "OpenRouter provider routed chat completion failed"
                    + " (providers=" + providerOrder
                    + ", model=" + OpenAiCompatibleChatModelSupport.nvl(modelName)
                    + ", error=" + OpenAiCompatibleChatModelSupport.shorten(e.getMessage())
                    + ", request=" + OpenAiCompatibleChatModelSupport.shorten(requestJson) + ")";
            log.warn(detail, e);
            throw new IllegalStateException(detail, e);
        }
    }
    
    /**
     * 上报 LLM 调用观测数据（ALP-25）。
     *
     * <p>这个重载保留给没有 retry attempts 的旧调用点。实际主路径会调用下面带
     * attempts 参数的版本，把每次 HTTP attempt 的状态码、耗时、错误摘要一起写入 trace。</p>
     */
    private String reportLlmCall(
            String llmCallId,
            RawHttpLogger.HttpRequestRecord request,
            RawHttpLogger.HttpResponseRecord response,
            String curlCommand,
            long startedAtMillis,
            long durationMs,
            String errorMessage,
            String thinkingContent,
            StreamingProgressTracker.StreamingProgressSnapshot streamingProgress) {
        return reportLlmCall(llmCallId, request, response, curlCommand, startedAtMillis, durationMs, errorMessage,
                thinkingContent, streamingProgress, List.of());
    }

    private String reportLlmCall(
            String llmCallId,
            RawHttpLogger.HttpRequestRecord request,
            RawHttpLogger.HttpResponseRecord response,
            String curlCommand,
            long startedAtMillis,
            long durationMs,
            String errorMessage,
            String thinkingContent,
            StreamingProgressTracker.StreamingProgressSnapshot streamingProgress,
            List<Map<String, Object>> attempts) {
        
        /*
         * raw HTTP trace 只在 runId 存在时写入，因为它是 run 详情页的一部分。
         * 这里会再次从 response 中提取 tokenUsage/cachedTokens，而不是复用 doChat
         * 中的 TokenUsage，原因是异常路径可能没有成功构造 ChatCompletionResponse，
         * 但 responseRecord 中仍然可能包含 provider 返回的 usage 或错误体。
         *
         * llmCallId 由 SSE live event 和 observability 共用。Step 2 存储治理后，
         * raw 请求/响应会被拆到 Redis detail blob，snapshot 中只保留安全摘要索引。
         */
        if (observabilityService == null) {
            return null;
        }
        
        String runId = AgentContext.getRunId();
        String phase = AgentContext.getPhase();
        
        if (runId == null || runId.isBlank()) {
            return null;
        }
        
        TokenUsage tokenUsage = OpenAiCompatibleChatModelSupport.extractTokenUsageFromResponse(objectMapper, response, log);
        Integer cachedTokens = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        long completedAtMillis = startedAtMillis + durationMs;
        
        return observabilityService.recordLlmCallWithRawHttp(
                runId,
                phase != null ? phase : "unknown",
                tokenUsage,
                cachedTokens,
                durationMs,
                startedAtMillis,
                completedAtMillis,
                endpointName,
                modelName,
                errorMessage,
                thinkingContent,
                streamingProgress,
                request,
                response,
                curlCommand,
                attempts,
                llmCallId
        );
    }

    private StreamingProgressTracker createStreamingProgressTracker(String llmTraceId) {
        /*
         * StreamingProgressTracker 解决的是长输出阶段「服务端还在流式返回，但前端看起来没动」
         * 的问题。它会按配置间隔写入 chunk 数、字符数、phase 等轻量进度，使 matrix poll
         * 能区分卡死、慢 summarizing、正常 tool execution 三种状态。
         *
         * 进度上报拆分为 observability（可选）和 live event（可选）两条独立链路：
         * 任一链路开启时都会创建 tracker 并触发 ProgressReporter 回调，回调内部再各自判断。
         */
        String runId = AgentContext.getRunId();
        String phase = AgentContext.getPhase();
        boolean observabilityEnabled = isStreamingProgressReportEnabled()
                && observabilityService != null
                && runId != null
                && !runId.isBlank();
        boolean liveEventEnabled = eventService != null
                && runId != null
                && !runId.isBlank();
        boolean reportEnabled = observabilityEnabled || liveEventEnabled;
        return new StreamingProgressTracker(
                log,
                modelName,
                endpointName,
                isSseProgressLogEnabled(),
                reportEnabled,
                streamingProgressUpdateIntervalMs(),
                (snapshot, completed) -> {
                    // 写 observability（原有行为，条件已在前方判断）
                    if (observabilityEnabled) {
                        observabilityService.recordStreamingProgress(
                                runId,
                                phase != null ? phase : "unknown",
                                endpointName,
                                modelName,
                                snapshot,
                                completed
                        );
                    }
                    // emit SSE live event（独立于 observability 开关）
                    emitLlmCallDelta(llmTraceId, snapshot);
                }
        );
    }

    private AttemptResult sendWithRetry(String requestJson,
                                        String requestUrl,
                                        String apiKey,
                                        Map<String, String> customHeaders,
                                        boolean shouldCapture,
                                        long logicalStartedAt,
                                        LlmRequestRetryPolicy retryPolicy,
                                        Duration requestTimeout,
                                        String llmTraceId) throws IOException, InterruptedException {
        /*
         * 这里把多个 HTTP attempt 包装成一个 AttemptResult 返回给 doChat。
         * 调用方只看到一次 ChatResponse；observability 才会看到每次 attempt 的细节。
         * 这样可以避免 provider 轻微抖动直接暴露给 agent loop，同时保留排障证据。
         *
         * 业务层 provider 顺序重排（ALP-25 增强）：
         * - 当 OpenRouter endpoint 且 providerOrder.size() > 1 时，
         *   每次重试前将上次首选的 provider 移到 order 末尾，尝试下一个 provider。
         * - 始终保持 allow_fallbacks=false，不把 provider 选择权交给 OpenRouter。
         * - 单 provider 时跳过重排，避免无意义循环。
         *
         * 2xx 后的 SSE body 读取也属于同一次 provider HTTP attempt。此前 2xx
         * 一返回就结束 retry，导致 stream 中途断开不会重试；现在只有完整聚合
         * SSE 成功才算 attempt 成功。
         */
        List<Map<String, Object>> attempts = new ArrayList<>();
        Exception lastException = null;
        RawHttpLogger.HttpResponseRecord lastResponseRecord = null;
        String lastErrorBody = null;
        int lastStatusCode = -1;
        LlmRequestRetryPolicy effectivePolicy = retryPolicy == null
                ? LlmRequestRetryPolicy.withMaxRetries(null)
                : retryPolicy;
        int cappedAttempts = effectivePolicy.maxAttempts();

        // 当前请求体（可能在重试时被修改）和 provider 顺序
        String currentRequestJson = requestJson;
        List<String> currentProviderOrder = (providerOrder == null || providerOrder.isEmpty())
                ? null : new ArrayList<>(providerOrder);
        boolean isOpenRouter = isOpenRouterEndpoint(baseUrl);

        for (int attempt = 1; attempt <= cappedAttempts; attempt++) {
            effectivePolicy.checkAttemptBudget(attempt);
            if (budgetService != null) {
                // HTTP attempt 预算和 LLM call 预算分开：一次逻辑 LLM 调用可能因为网络/供应商抖动重发。
                // 如果不单独计数，单个 LLM step 可能在 provider 层消耗过多时间而业务层看不出来。
                budgetService.checkHttpAttempt(attempt);
            }

            // 重试前重排 provider order（业务层，非 OpenRouter fallback）
            if (attempt > 1 && isOpenRouter && currentProviderOrder != null && currentProviderOrder.size() > 1) {
                List<String> reordered = rotateProviderOrder(currentProviderOrder);
                if (!reordered.equals(currentProviderOrder)) {
                    currentProviderOrder = reordered;
                    // request body 里 provider.order 是 OpenRouter 选择供应商的唯一输入。
                    // 只改本次重试的 body，不修改 endpoint 配置对象，避免影响后续 run。
                    String rebuilt = rebuildRequestWithProviderOrder(currentRequestJson, currentProviderOrder);
                    if (rebuilt != null) {
                        currentRequestJson = rebuilt;
                        if (log.isDebugEnabled()) {
                            log.debug("Provider order rotated for retry: attempt={}, newOrder={}",
                                    attempt, currentProviderOrder);
                        }
                    }
                }
            }

            long attemptStarted = System.currentTimeMillis();
            try {
                HttpRequest.Builder httpRequestBuilder = buildHttpRequest(
                        requestUrl, apiKey, customHeaders, currentRequestJson, requestTimeout);
                HttpResponse<java.io.InputStream> httpResponse = HTTP_CLIENT.send(
                        httpRequestBuilder.build(),
                        HttpResponse.BodyHandlers.ofInputStream()
                );
                int status = httpResponse.statusCode();
                long attemptDuration = System.currentTimeMillis() - attemptStarted;
                Map<String, Object> attemptMeta = new LinkedHashMap<>();
                attemptMeta.put("attempt", attempt);
                attemptMeta.put("httpStatus", status);
                attemptMeta.put("durationMs", attemptDuration);
                attemptMeta.put("timeoutSeconds", requestTimeout.toSeconds());
                if (currentProviderOrder != null) {
                    attemptMeta.put("providerOrder", currentProviderOrder);
                }
                attempts.add(attemptMeta);

                if (status >= 200 && status < 300) {
                    // 流式响应：解析 SSE。
                    //
                    // OpenRouter/Fireworks 的流式响应会把 content、reasoning、tool_calls、
                    // usage 等信息拆在多个 chunk 中返回。这里用聚合器还原成一个
                    // ChatCompletionResponse，后续 LangChain4j 才能像非流式响应一样读取
                    // AiMessage 和 TokenUsage。
                    StreamingProgressTracker tracker = createStreamingProgressTracker(llmTraceId);
                    try {
                        OpenAiCompatibleChatModelSupport.SseAggregateResult aggregateResult =
                                OpenAiCompatibleChatModelSupport.aggregateSseStream(
                                        httpResponse.body(), objectMapper, log, tracker, STREAM_IDLE_TIMEOUT
                                );
                        long streamDuration = System.currentTimeMillis() - attemptStarted;
                        attemptMeta.put("streamDurationMs", streamDuration);
                        StreamingProgressTracker.StreamingProgressSnapshot progressSnapshot =
                                tracker.onStreamComplete(System.currentTimeMillis() - logicalStartedAt);
                        return new AttemptResult(httpResponse, null, status, null, attempts,
                                aggregateResult, progressSnapshot, null);
                    } catch (RuntimeException e) {
                        long streamDuration = System.currentTimeMillis() - attemptStarted;
                        lastException = e;
                        lastStatusCode = status;
                        lastErrorBody = e.getClass().getSimpleName() + ": " + e.getMessage();
                        boolean retryable = isRetryableStreamingException(e);
                        attemptMeta.put("streamDurationMs", streamDuration);
                        attemptMeta.put("retryable", retryable);
                        attemptMeta.put("error", OpenAiCompatibleChatModelSupport.shorten(lastErrorBody));
                        if (!shouldRetryStreamingException(e, effectivePolicy, attempt)) {
                            return new AttemptResult(httpResponse, lastResponseRecord, status, lastErrorBody,
                                    attempts, null, null, e);
                        }
                    }
                } else {
                    // 非 2xx 响应通常不是 SSE 流，而是一次性错误 JSON。必须先读出 body，
                    // 否则 raw HTTP capture 和失败 payload 都拿不到 provider 返回的真实原因。
                    String body;
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(httpResponse.body(), StandardCharsets.UTF_8))) {
                        body = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                    }
                    lastErrorBody = body;
                    lastStatusCode = status;
                    if (shouldCapture && httpLogger != null) {
                        Map<String, String> responseHeaders = httpLogger.extractHeaders(httpResponse);
                        lastResponseRecord = httpLogger.recordResponse(
                                status, responseHeaders, body, System.currentTimeMillis() - logicalStartedAt);
                    }
                    attemptMeta.put("retryable", effectivePolicy.isRetryableStatus(status));
                    attemptMeta.put("error", OpenAiCompatibleChatModelSupport.shorten(body));
                    if (!effectivePolicy.shouldRetryStatus(status, attempt)) {
                        // 不可重试状态或次数耗尽时，把最后一次 HTTP response 连同 attempts 返回，
                        // doChat 统一转换成异常观测和 LLM_CALL_FINISHED(error) 事件。
                        return new AttemptResult(httpResponse, lastResponseRecord, status, body,
                                attempts, null, null, null);
                    }
                }
            } catch (IOException e) {
                long attemptDuration = System.currentTimeMillis() - attemptStarted;
                lastException = e;
                Map<String, Object> attemptMeta = new LinkedHashMap<>();
                attemptMeta.put("attempt", attempt);
                attemptMeta.put("durationMs", attemptDuration);
                attemptMeta.put("timeoutSeconds", requestTimeout.toSeconds());
                attemptMeta.put("retryable", effectivePolicy.isRetryableException(e));
                attemptMeta.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                if (currentProviderOrder != null) {
                    attemptMeta.put("providerOrder", currentProviderOrder);
                }
                attempts.add(attemptMeta);
                if (!effectivePolicy.shouldRetryException(e, attempt)) {
                    // IOException 没有 HTTP status，也可能没有 response record。
                    // 仍然返回 AttemptResult，是为了让 observability 展示完整 attempts，而不是只看到最后异常。
                    return new AttemptResult(null, lastResponseRecord, -1,
                            e.getClass().getSimpleName() + ": " + e.getMessage(),
                            attempts, null, null, null);
                }
            }
            effectivePolicy.sleepBeforeRetry(attempt);
        }
        // 不再向上抛 raw IOException，让 doChat 统一包装成 ProviderChatException 并分类。
        return new AttemptResult(null, lastResponseRecord, lastStatusCode, lastErrorBody, attempts,
                null, null, lastException);
    }

    private boolean shouldRetryStreamingException(Exception e, LlmRequestRetryPolicy policy, int attempt) {
        return isRetryableStreamingException(e)
                && policy != null
                && attempt < policy.maxAttempts();
    }

    private boolean isRetryableStreamingException(Exception e) {
        if (e instanceof IOException) {
            return true;
        }
        if (e instanceof IllegalStateException && e.getCause() instanceof IOException) {
            return true;
        }
        return false;
    }

    /**
     * 构建 HTTP 请求 Builder。每次重试时独立构建，以便 request body 可能被修改。
     */
    private HttpRequest.Builder buildHttpRequest(String requestUrl, String apiKey,
                                                  Map<String, String> customHeaders,
                                                  String requestJson, Duration requestTimeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + OpenAiCompatibleChatModelSupport.nvl(apiKey))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

        if (customHeaders != null && !customHeaders.isEmpty()) {
            for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
        }
        return builder;
    }

    /**
     * 对 provider 返回的错误进行分类，产生稳定的 {@link ProviderFailureCategory}。
     *
     * <p>分类优先级（从高到低）：
     * token limit / bad request → rate limit → auth rejected → model unavailable →
     * transient network → unknown。</p>
     */
    private ProviderFailureCategory classifyProviderError(int statusCode,
                                                          String errorBody,
                                                          Throwable cause,
                                                          List<String> currentProviderOrder) {
        String code = extractErrorCodeFromBody(errorBody);
        String bodyLower = errorBody == null ? "" : errorBody.toLowerCase(Locale.ROOT);
        String codeLower = code.toLowerCase(Locale.ROOT);

        // 1. Token / context length / bad request
        if (statusCode == 400
                || "bad_request".equals(codeLower)
                || "invalid_request_error".equals(codeLower)) {
            if (codeLower.contains("context_length_exceeded")
                    || codeLower.contains("max_tokens")
                    || codeLower.contains("token")
                    || bodyLower.contains("context_length_exceeded")
                    || bodyLower.contains("max_tokens")
                    || bodyLower.contains("context length")) {
                return ProviderFailureCategory.BAD_REQUEST_TOKEN_LIMIT;
            }
        }

        // 2. Rate limit
        if (statusCode == 429
                || codeLower.contains("rate_limit")
                || codeLower.contains("rate limit")
                || bodyLower.contains("rate limit")
                || bodyLower.contains("too many requests")) {
            return ProviderFailureCategory.RATE_LIMIT;
        }

        // 3. Auth rejected
        if (statusCode == 401 || statusCode == 403
                || codeLower.contains("unauthorized")
                || codeLower.contains("forbidden")
                || codeLower.contains("auth")
                || codeLower.contains("api_key")) {
            return ProviderFailureCategory.AUTH_REJECTED;
        }

        // 4. Model unavailable
        if (statusCode == 404
                || codeLower.contains("model_not_found")
                || codeLower.contains("model_unavailable")
                || codeLower.contains("not_found")
                || bodyLower.contains("model_not_found")
                || bodyLower.contains("model_unavailable")) {
            return ProviderFailureCategory.MODEL_UNAVAILABLE;
        }

        // 5. Transient network
        if (statusCode == -1
                || (statusCode >= 500 && statusCode <= 599)
                || codeLower.contains("timeout")
                || codeLower.contains("connection")
                || codeLower.contains("internal server error")
                || bodyLower.contains("network connection lost")
                || bodyLower.contains("connection reset")
                || bodyLower.contains("connection refused")
                || bodyLower.contains("timeout")
                || bodyLower.contains("timed out")
                || bodyLower.contains("upstream unavailable")
                || cause instanceof IOException) {
            return ProviderFailureCategory.TRANSIENT_NETWORK;
        }

        return ProviderFailureCategory.UNKNOWN;
    }

    /**
     * 从 provider 错误 JSON 中提取结构化错误码（{@code code} 或 {@code error.code}）。
     * 解析失败时返回空串。
     */
    @SuppressWarnings("unchecked")
    private String extractErrorCodeFromBody(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> root = objectMapper.readValue(errorBody, new TypeReference<Map<String, Object>>() {
            });
            Object code = root.get("code");
            if (code instanceof String s) {
                return s;
            }
            Object error = root.get("error");
            if (error instanceof Map<?, ?> errorMap) {
                Object nestedCode = ((Map<String, Object>) errorMap).get("code");
                if (nestedCode instanceof String s) {
                    return s;
                }
                Object nestedMessage = ((Map<String, Object>) errorMap).get("message");
                if (nestedMessage instanceof String s) {
                    return s;
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体返回空串，后续用 keyword fallback
        }
        return "";
    }

    /**
     * 将 provider order 列表左旋一位：第一个 provider 移到末尾。
     * 这样下次重试会优先尝试列表中的下一个 provider。
     */
    private List<String> rotateProviderOrder(List<String> currentOrder) {
        if (currentOrder == null || currentOrder.size() <= 1) {
            // 单 provider 时不能“切换”，保持原顺序可让失败原因更直接。
            return currentOrder;
        }
        List<String> result = new ArrayList<>(currentOrder);
        String first = result.remove(0);
        result.add(first);
        return result;
    }

    /**
     * 修改请求 JSON 中的 provider.order 字段。
     * 保持 allow_fallbacks=false 不变。
     */
    @SuppressWarnings("unchecked")
    private String rebuildRequestWithProviderOrder(String requestJson, List<String> newProviderOrder) {
        try {
            Map<String, Object> requestMap = objectMapper.readValue(requestJson, new TypeReference<Map<String, Object>>() {});
            Object providerObj = requestMap.get("provider");
            if (providerObj instanceof Map) {
                Map<String, Object> provider = (Map<String, Object>) providerObj;
                provider.put("order", newProviderOrder);
                // 确保 allow_fallbacks 始终为 false
                // provider 顺序由业务层显式控制，不交给 OpenRouter 自动 fallback，
                // 这样压测和排障时能解释每次请求到底打到了哪个候选列表。
                provider.put("allow_fallbacks", false);
            }
            return objectMapper.writeValueAsString(requestMap);
        } catch (Exception e) {
            log.warn("Failed to rebuild request with reordered provider order, using original: {}", e.getMessage());
            return null;
        }
    }

    private Duration resolveRequestTimeout() {
        return resolveRequestTimeout(AgentContext.getStage(), AgentContext.getPhase());
    }

    private LlmRequestRetryPolicy resolveRetryPolicy() {
        AgentLlmProperties.Request requestConfig = resolveRequestConfig();
        return LlmRequestRetryPolicy.withConfig(
                resolveRequestMaxRetries(requestConfig),
                requestConfig == null ? null : requestConfig.getRetry()
        );
    }

    private Integer resolveRequestMaxRetries(AgentLlmProperties.Request requestConfig) {
        Integer configured = requestConfig == null ? null : requestConfig.getMaxRetries();
        if (configured != null && configured >= 0) {
            return configured;
        }
        if (budgetService != null) {
            return Math.max(0, budgetService.maxHttpAttemptsPerLogicalCall() - 1);
        }
        return LlmRequestRetryPolicy.DEFAULT_MAX_RETRIES;
    }

    private AgentLlmProperties.Request resolveRequestConfig() {
        if (localConfigLoader == null) {
            return null;
        }
        return localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getRequest)
                .orElse(null);
    }

    public static Duration resolveRequestTimeout(String stageValue, String phaseValue) {
        /*
         * 超时时间按阶段分层：
         * - planning / final answer 需要较长时间，因为输出结构化计划或最终答案；
         * - judge / decision / execute 等小模型决策应快速返回；
         * - 其它阶段用中间值。
         *
         * 这里不是 run wall-clock budget。单次 HTTP timeout 只约束一次模型请求，
         * run budget 仍由 AgentRunBudgetService 统一判断。
         */
        String stage = OpenAiCompatibleChatModelSupport.nvl(stageValue).toLowerCase();
        String phase = OpenAiCompatibleChatModelSupport.nvl(phaseValue).toLowerCase();
        if (phase.contains("planning") || stage.contains("planning") || stage.contains("final_answer")) {
            return Duration.ofSeconds(90);
        }
        if (stage.contains("semantic_judge")
                || stage.contains("search_evidence_judge")
                || stage.contains("tool_decision")
                || stage.endsWith("_decision")
                || stage.endsWith("_execute")
                || stage.endsWith("_plan")
                || phase.contains("decision")) {
            return Duration.ofSeconds(30);
        }
        return Duration.ofSeconds(60);
    }

    private record AttemptResult(
            HttpResponse<java.io.InputStream> response,
            RawHttpLogger.HttpResponseRecord responseRecord,
            int statusCode,
            String errorBody,
            List<Map<String, Object>> attempts,
            OpenAiCompatibleChatModelSupport.SseAggregateResult aggregateResult,
            StreamingProgressTracker.StreamingProgressSnapshot streamingProgress,
            Exception streamError
    ) {
    }

    private boolean isStreamingProgressReportEnabled() {
        return localConfigLoader == null
                || localConfigLoader.current()
                .map(AgentLlmProperties::getObservability)
                .map(AgentLlmProperties.Observability::getStreamingProgress)
                .map(AgentLlmProperties.StreamingProgress::getEnabled)
                .map(Boolean.TRUE::equals)
                .orElse(true);
    }

    private long streamingProgressUpdateIntervalMs() {
        return localConfigLoader == null ? 3000L
                : localConfigLoader.current()
                .map(AgentLlmProperties::getObservability)
                .map(AgentLlmProperties.Observability::getStreamingProgress)
                .map(AgentLlmProperties.StreamingProgress::getUpdateIntervalMs)
                .filter(v -> v != null && v > 0)
                .map(Integer::longValue)
                .orElse(3000L);
    }

    private boolean isSseProgressLogEnabled() {
        return localConfigLoader != null
                && localConfigLoader.current()
                .map(AgentLlmProperties::getDebug)
                .map(AgentLlmProperties.Debug::getLogSseProgress)
                .map(Boolean.TRUE::equals)
                .orElse(false);
    }

    private boolean isOpenRouterEndpoint(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            return isOpenRouterHost(uri.getHost());
        } catch (IllegalArgumentException e) {
            try {
                URI uri = new URI(url.trim());
                return isOpenRouterHost(uri.getHost());
            } catch (URISyntaxException ignored) {
                return false;
            }
        }
    }

    private boolean isFireworksEndpoint(String url) {
        return isFireworksEndpointUrl(url);
    }

    private boolean isOpenRouterHost(String host) {
        return host != null && (host.equals("openrouter.ai") || host.endsWith(".openrouter.ai"));
    }

    private static boolean isFireworksHost(String host) {
        return host != null && (host.equals("fireworks.ai") || host.endsWith(".fireworks.ai"));
    }

    public static void normalizeOpenRouterTokenLimit(Map<String, Object> requestJsonMap) {
        if (requestJsonMap == null) {
            return;
        }
        Object maxCompletionTokens = requestJsonMap.remove("max_completion_tokens");
        // OpenRouter 的 provider require_parameters 会按请求字段过滤供应商。
        // 对 Kimi/Fireworks 等 OpenAI 兼容模型，max_completion_tokens 会导致供应商被过滤；
        // 使用 OpenRouter Chat Completions 通用字段 max_tokens 更稳定。
        if (maxCompletionTokens != null && !requestJsonMap.containsKey("max_tokens")) {
            requestJsonMap.put("max_tokens", maxCompletionTokens);
        }
    }

    public static void applyStreamingOptions(Map<String, Object> requestJsonMap, String baseUrl) {
        applyStreamingOptions(requestJsonMap, baseUrl, AgentContext.getPhase());
    }

    /**
     * OpenRouter 流式选项。planning 阶段跳过 {@code stream_options}，避免在
     * {@code provider.require_parameters=true} 时因 provider 未声明支持该字段而被误过滤。
     *
     * <p>这个方法看起来像 provider 兼容细节，但实际会影响工具调用是否能启动：
     * OpenRouter 先按请求参数筛 provider，再转发请求。如果某个可用 provider 没声明
     * {@code stream_options}，即使它支持模型本身，也可能被过滤成 404。</p>
     */
    public static void applyStreamingOptions(Map<String, Object> requestJsonMap, String baseUrl, String phase) {
        if (requestJsonMap == null) {
            return;
        }
        if (isFireworksEndpointUrl(baseUrl)) {
            // Fireworks 当前 API 文档没有列出 stream_options；流式 perf metrics 通过最终 chunk 返回。
            requestJsonMap.remove("stream_options");
            requestJsonMap.put("perf_metrics_in_response", true);
            return;
        }
        if (AgentObservabilityService.PHASE_PLANNING.equals(phase)) {
            requestJsonMap.remove("stream_options");
            requestJsonMap.remove("perf_metrics_in_response");
            return;
        }
        requestJsonMap.put("stream_options", Map.of("include_usage", true));
        requestJsonMap.remove("perf_metrics_in_response");
    }

    public static void applyFireworksReasoningEffort(Map<String, Object> requestJsonMap, String reasoningEffort) {
        if (requestJsonMap == null || reasoningEffort == null || reasoningEffort.isBlank()) {
            return;
        }
        requestJsonMap.put("reasoning_effort", reasoningEffort);
    }

    public static void applyEndpointSamplingDefaults(Map<String, Object> requestJsonMap, String baseUrl) {
        if (requestJsonMap == null) {
            return;
        }
        if (isFireworksEndpointUrl(baseUrl)) {
            // Fireworks 实验使用服务端默认采样参数，避免本地全局默认 temperature 干扰。
            requestJsonMap.remove("temperature");
        }
    }

    private static boolean isFireworksEndpointUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            return isFireworksHost(uri.getHost());
        } catch (IllegalArgumentException e) {
            try {
                URI uri = new URI(url.trim());
                return isFireworksHost(uri.getHost());
            } catch (URISyntaxException ignored) {
                return false;
            }
        }
    }

    /**
     * 检查是否开启 curl debug 日志（热加载）。
     */
    private boolean isDebugCurlEnabled() {
        // 与上方 [LLM Debug CURL] 一并临时关闭，避免 Nacos debug.logLlmCurl=true 时仍打全量请求
        return false;
        // if (localConfigLoader == null) {
        //     return false;
        // }
        // return localConfigLoader.current()
        //         .map(cfg -> cfg.getDebug())
        //         .map(debug -> debug.getLogLlmCurl())
        //         .orElse(false);
    }
    
    /**
     * 构建 curl 命令字符串。
     */
    private String buildCurlCommand(String url, Map<String, String> headers, String body) {
        StringBuilder curl = new StringBuilder();
        curl.append("curl -X POST \\\n");
        curl.append("  \"").append(url).append("\" \\\n");
        
        if (headers != null) {
            headers.forEach((key, value) -> {
                String headerName = key.toLowerCase();
                if (headerName.contains("authorization")) {
                    curl.append("  -H \"").append(key).append(": Bearer $API_KEY\" \\\n");
                } else {
                    curl.append("  -H \"").append(key).append(": ").append(value).append("\" \\\n");
                }
            });
        }
        
        if (body != null && !body.isEmpty()) {
            String escapedBody = body.replace("'", "'\"'\"'");
            curl.append("  -d '").append(escapedBody).append("'");
        }
        
        return curl.toString();
    }

    // ── SSE live event helpers ──
    //
    // 这些事件是 Agent V2 前端主链路的数据源。STARTED/DELTA/FINISHED 都要带同一个
    // llm_call_id / trace_id，以及 AgentContext 中的 phase、stage、todo_id、workflow。
    // planning/summarizing 阶段可能没有 todo 归属；execution 阶段必须能挂到具体节点。

    private void emitLlmCallStarted(String llmTraceId, boolean stream) {
        if (eventService == null) {
            return;
        }
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        if (runId == null || userId == null) {
            log.debug("Skip LLM_CALL_STARTED: missing runId or userId in AgentContext");
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        AgentSsePayloadSupport.putLlmCallIds(payload, llmTraceId);
        payload.put("model", nvl(modelName));
        payload.put("endpoint", nvl(endpointName));
        payload.put("phase", nvl(AgentContext.getPhase()));
        AgentSsePayloadSupport.putExecutionAttribution(payload);
        payload.put("stream", stream);
        payload.put("started_at_ms", System.currentTimeMillis());
        try {
            eventService.append(runId, userId, "LLM_CALL_STARTED", payload);
        } catch (Exception e) {
            log.warn("LLM_CALL_STARTED event emit failed (ignored): {}", e.getMessage());
        }
    }

    private void emitLlmCallDelta(String llmTraceId, StreamingProgressTracker.StreamingProgressSnapshot snapshot) {
        if (eventService == null || snapshot == null) {
            return;
        }
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        if (runId == null || userId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        AgentSsePayloadSupport.putLlmCallIds(payload, llmTraceId);
        AgentSsePayloadSupport.putExecutionAttribution(payload);
        payload.put("content_chars", snapshot.contentCharCount());
        payload.put("reasoning_chars", snapshot.reasoningCharCount());
        payload.put("tool_call_chars", snapshot.toolCallCharCount());
        payload.put("chunk_count", snapshot.chunkCount());
        payload.put("chars_per_second", Math.round(snapshot.charsPerSecond() * 10.0) / 10.0);
        // 保守估算：按 1 token ≈ 4 chars（英文字符）做上限估算
        payload.put("estimated_output_tokens", snapshot.totalCharCount() / 4);
        try {
            eventService.append(runId, userId, "LLM_CALL_DELTA", payload);
        } catch (Exception e) {
            log.warn("LLM_CALL_DELTA event emit failed (ignored): {}", e.getMessage());
        }
    }

    private void emitLlmCallFinished(String llmTraceId,
                                      TokenUsage tokenUsage,
                                      long durationMs,
                                      Double actualCost,
                                      String generationId,
                                      String errorPreview,
                                      String observabilityTraceId,
                                      List<Map<String, Object>> attempts) {
        if (eventService == null) {
            return;
        }
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        if (runId == null || userId == null) {
            log.debug("Skip LLM_CALL_FINISHED: missing runId or userId in AgentContext");
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        AgentSsePayloadSupport.putLlmCallIds(payload, llmTraceId);
        payload.put("model", nvl(modelName));
        payload.put("endpoint", nvl(endpointName));
        payload.put("phase", nvl(AgentContext.getPhase()));
        AgentSsePayloadSupport.putExecutionAttribution(payload);
        payload.put("duration_ms", Math.max(0L, durationMs));
        payload.put("success", errorPreview == null);
        putAttemptPayload(payload, attempts);
        if (errorPreview != null) {
            payload.put("error_preview", errorPreview.length() > 500 ? errorPreview.substring(0, 500) : errorPreview);
        }
        if (tokenUsage != null) {
            payload.put("input_tokens", tokenUsage.inputTokenCount());
            payload.put("output_tokens", tokenUsage.outputTokenCount());
            payload.put("total_tokens", tokenUsage.totalTokenCount());
        }
        if (actualCost != null) {
            payload.put("actual_cost", actualCost);
        }
        if (generationId != null && !generationId.isBlank()) {
            payload.put("generation_id", generationId);
        }
        if (observabilityTraceId != null && !observabilityTraceId.isBlank()) {
            payload.put("observability_trace_id", observabilityTraceId);
        }
        try {
            eventService.append(runId, userId, "LLM_CALL_FINISHED", payload);
        } catch (Exception e) {
            log.warn("LLM_CALL_FINISHED event emit failed (ignored): {}", e.getMessage());
        }
    }

    private void putAttemptPayload(Map<String, Object> payload, List<Map<String, Object>> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            payload.put("attempt_count", 0);
            return;
        }
        payload.put("attempt_count", attempts.size());
        payload.put("attempts", attempts);
    }

    /**
     * 从 SSE 聚合结果中的 usage Map 提取 actual cost。
     * 不依赖 raw HTTP capture，确保正常 run 也能拿到 cost。
     */
    @SuppressWarnings("unchecked")
    private Double extractActualCostFromUsage(Map<String, Object> usage) {
        if (usage == null) {
            return null;
        }
        Object cost = usage.get("cost");
        if (cost instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

}
