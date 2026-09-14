package world.willfrog.agentlangchain.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.finance.FinanceMethodResolution;
import world.willfrog.agent.platform.finance.FinanceMethodResolutionQuery;
import world.willfrog.agent.platform.finance.FinanceMetricRecord;
import world.willfrog.agent.platform.finance.FinanceRecordQuery;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.tools.finance.FinanceMethodSpec;
import world.willfrog.agent.tools.finance.FinanceMethodSpecCatalog;
import world.willfrog.agent.tools.finance.FinanceResultModelProjector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FinanceResultComposer} 单测（Spec §11）。
 *
 * <p>覆盖：记录→确定性三列块、记录级 fail-closed 跳过、跨环境后台核对事件、
 * 稳定 blockId/dedupeKey、denylist、异常退回模型原文。</p>
 */
class FinanceResultComposerTest {

    private static final String RUN_ID = "run-1";
    private static final String USER_ID = "user-1";

    private FinanceRecordQuery recordQuery;
    private FinanceMethodResolutionQuery resolutionQuery;
    private FinanceResultModelProjector projector;
    private FinanceMethodSpecCatalog specCatalog;
    private AgentRunEventService eventService;
    private FinanceResultComposer composer;

    @BeforeEach
    void setUp() {
        recordQuery = mock(FinanceRecordQuery.class);
        resolutionQuery = mock(FinanceMethodResolutionQuery.class);
        projector = mock(FinanceResultModelProjector.class);
        specCatalog = mock(FinanceMethodSpecCatalog.class);
        eventService = mock(AgentRunEventService.class);
        composer = new FinanceResultComposer(
                recordQuery, resolutionQuery, projector, specCatalog,
                new FinanceResultBlockRenderer(), eventService, new ObjectMapper());
        lenient().when(specCatalog.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(eventService.appendOnce(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
    }

    // ========== 基本组合 ==========

    @Test
    void append_shouldAppendDeterministicBlockAfterModelText() {
        FinanceMetricRecord record = renderableRecord("rec-1", 0);
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(record));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "模型说明。");

        assertThat(result).startsWith("模型说明。\n\n");
        String markdown = result.substring("模型说明。\n\n".length());
        assertThat(markdown).startsWith("| 方法 | 结果 | 如何计算 |\n|---|---:|---|\n");
        assertThat(markdown).contains("| M:tushare_index_daily | 123.45 | H:收盘价均值 |");
    }

    @Test
    void append_shouldReturnMarkdownOnlyWhenModelTextBlank() {
        FinanceMetricRecord record = renderableRecord("rec-1", 0);
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(record));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "  ");

        assertThat(result).startsWith("| 方法 | 结果 | 如何计算 |");
        assertThat(result).doesNotStartWith("\n");
    }

    @Test
    void append_shouldOrderRowsByRecordIndexExactly() {
        FinanceMetricRecord recLate = renderableRecord("rec-late", 5).toBuilder()
                .formulaDescription("第五步").build();
        FinanceMetricRecord recNull = renderableRecord("rec-null", null).toBuilder()
                .formulaDescription("无序号").build();
        FinanceMetricRecord recEarly = renderableRecord("rec-early", 1).toBuilder()
                .formulaDescription("第一步").build();
        when(recordQuery.listRenderableByRun(RUN_ID))
                .thenReturn(new ArrayList<>(List.of(recLate, recNull, recEarly)));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "");

        int posEarly = result.indexOf("H:第一步");
        int posLate = result.indexOf("H:第五步");
        int posNull = result.indexOf("H:无序号");
        assertThat(posEarly).isGreaterThanOrEqualTo(0);
        assertThat(posLate).isGreaterThan(posEarly);
        assertThat(posNull).isGreaterThan(posLate);
    }

    // ========== 空/跳过路径 ==========

    @Test
    void append_shouldReturnModelTextWhenNoRecords() {
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of());

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "原文");

        assertThat(result).isEqualTo("原文");
        verifyNoInteractions(eventService);
    }

    @Test
    void append_shouldReturnModelTextWhenRunIdBlank() {
        String result = composer.appendFinanceResultBlock(" ", USER_ID, "原文");

        assertThat(result).isEqualTo("原文");
        verifyNoInteractions(recordQuery, eventService);
    }

    @Test
    void append_shouldReturnModelTextWhenUserIdBlank() {
        String result = composer.appendFinanceResultBlock(RUN_ID, null, "原文");

        assertThat(result).isEqualTo("原文");
        verifyNoInteractions(recordQuery, eventService);
    }

    @Test
    void append_shouldSkipRecordWhenValueJsonNotNumber() {
        FinanceMetricRecord bad = renderableRecord("rec-bad", 0).toBuilder()
                .valueJson("{\"not\":\"a-number\"}").build();
        FinanceMetricRecord good = renderableRecord("rec-good", 1);
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(bad, good));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        // bad 记录被跳过：projector 只被调用一次（good）
        verify(projector, times(1)).project(any());
        assertThat(result).contains("M:tushare_index_daily");
        // RENDERED 事件 record.count = 1
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventService).appendOnce(eq(RUN_ID), eq(USER_ID),
                eq(FinanceResultComposer.EVENT_RESULT_BLOCK_RENDERED), anyString(), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload.get("finance.record.count")).isEqualTo(1);
    }

    @Test
    void append_shouldSkipRecordWhenDeclaredEvidenceUnparseable() {
        FinanceMetricRecord bad = renderableRecord("rec-bad", 0).toBuilder()
                .declaredEvidence("BOGUS_VALUE").build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(bad));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "原文");

        assertThat(result).isEqualTo("原文");
        verify(projector, never()).project(any());
        verifyNoInteractions(eventService);
    }

    @Test
    void append_shouldSkipRecordWhenProjectorReturnsEmpty() {
        FinanceMetricRecord bad = renderableRecord("rec-bad", 0).toBuilder()
                .methodId("unprojectable_method").build();
        FinanceMetricRecord good = renderableRecord("rec-good", 1);
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(bad, good));
        stubProjectorEcho();
        when(projector.project(argThat(in -> in != null && "unprojectable_method".equals(in.methodId()))))
                .thenReturn(Optional.empty());

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        assertThat(result).contains("M:tushare_index_daily");
        assertThat(result).doesNotContain("unprojectable_method");
    }

    @Test
    void append_shouldReturnModelTextWhenAllRecordsSkipped() {
        FinanceMetricRecord bad = renderableRecord("rec-bad", 0).toBuilder()
                .valueJson("null").build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(bad));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "原文");

        assertThat(result).isEqualTo("原文");
        verifyNoInteractions(eventService);
    }

    // ========== 跨环境后台核对 ==========

    @Test
    void append_shouldWriteCrossEnvironmentEventWithStableDedupeKey() {
        FinanceMetricRecord record = renderableRecord("rec-1", 0).toBuilder()
                .actualEnvironmentId("env-actual-a").build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(record));
        when(resolutionQuery.findExact(RUN_ID, "resolver-call-1",
                "tushare_index_daily", "1.0.0", "0123456789abcdef"))
                .thenReturn(FinanceMethodResolution.builder()
                        .targetEnvironmentId("env-target-b").build());
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        // 块仍渲染
        assertThat(result).contains("| 方法 | 结果 | 如何计算 |");
        // CROSS_ENVIRONMENT 事件：稳定去重键 + 完整 payload
        ArgumentCaptor<String> dedupeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventService).appendOnce(eq(RUN_ID), eq(USER_ID),
                eq(FinanceResultComposer.EVENT_CROSS_ENVIRONMENT), dedupeCaptor.capture(), payloadCaptor.capture());
        assertThat(dedupeCaptor.getValue())
                .isEqualTo("FINANCE_CROSS_ENVIRONMENT:run-1:rec-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload.get("finance.run.id")).isEqualTo(RUN_ID);
        assertThat(payload.get("finance.record.id")).isEqualTo("rec-1");
        assertThat(payload.get("finance.environment.target")).isEqualTo("env-target-b");
        assertThat(payload.get("finance.environment.actual")).isEqualTo("env-actual-a");
    }

    @Test
    void append_shouldNotWriteCrossEnvironmentWhenSnapshotMissing() {
        FinanceMetricRecord record = renderableRecord("rec-1", 0).toBuilder()
                .actualEnvironmentId("env-actual-a").build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(record));
        when(resolutionQuery.findExact(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);
        stubProjectorEcho();

        composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        verify(eventService, never()).appendOnce(anyString(), anyString(),
                eq(FinanceResultComposer.EVENT_CROSS_ENVIRONMENT), anyString(), any());
    }

    @Test
    void append_shouldNotWriteCrossEnvironmentWhenEnvironmentsMatch() {
        FinanceMetricRecord record = renderableRecord("rec-1", 0).toBuilder()
                .actualEnvironmentId("env-a").build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(record));
        when(resolutionQuery.findExact(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(FinanceMethodResolution.builder().targetEnvironmentId("env-a").build());
        stubProjectorEcho();

        composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        verify(eventService, never()).appendOnce(anyString(), anyString(),
                eq(FinanceResultComposer.EVENT_CROSS_ENVIRONMENT), anyString(), any());
    }

    @Test
    void append_shouldNotWriteCrossEnvironmentWhenResolverToolCallIdBlank() {
        FinanceMetricRecord record = renderableRecord("rec-1", 0).toBuilder()
                .sourceResolverToolCallId(" ")
                .actualEnvironmentId("env-a").build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(record));
        stubProjectorEcho();

        composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        verifyNoInteractions(resolutionQuery);
        verify(eventService, never()).appendOnce(anyString(), anyString(),
                eq(FinanceResultComposer.EVENT_CROSS_ENVIRONMENT), anyString(), any());
    }

    // ========== 稳定 blockId / 事件 ==========

    @Test
    void append_shouldProduceStableBlockIdAndDedupeKeyAcrossRepeatCalls() {
        FinanceMetricRecord record = renderableRecord("rec-1", 0);
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(record));
        stubProjectorEcho();

        composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");
        composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        ArgumentCaptor<String> dedupeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventService, times(2)).appendOnce(eq(RUN_ID), eq(USER_ID),
                eq(FinanceResultComposer.EVENT_RESULT_BLOCK_RENDERED), dedupeCaptor.capture(), any());
        List<String> keys = dedupeCaptor.getAllValues();
        assertThat(keys.get(0)).startsWith("FINANCE_RESULT_BLOCK_RENDERED:sha256:");
        assertThat(keys.get(1)).isEqualTo(keys.get(0));
    }

    @Test
    void append_shouldWriteRenderedEventWithExpectedPayload() {
        FinanceMetricRecord record = renderableRecord("rec-1", 0).toBuilder()
                .actualEnvironmentId("env-a").build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(record));
        stubProjectorEcho();

        composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventService).appendOnce(eq(RUN_ID), eq(USER_ID),
                eq(FinanceResultComposer.EVENT_RESULT_BLOCK_RENDERED), anyString(), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload.get("finance.block.id").toString()).startsWith("sha256:");
        assertThat(payload.get("finance.record.count")).isEqualTo(1);
        assertThat(payload.get("finance.record.ids")).isEqualTo(List.of("rec-1"));
        assertThat(payload.get("finance.environment.id")).isEqualTo("env-a");
        assertThat(payload.get("finance.renderer.version"))
                .isEqualTo(FinanceResultBlockRenderer.RENDERER_VERSION);
    }

    @Test
    void append_shouldWriteRenderedEventWithOrderedRecordIdsAndSkipUnprojectableRecord() {
        FinanceMetricRecord recA = renderableRecord("rec-a", 0);
        FinanceMetricRecord recSkipped = renderableRecord("rec-skipped", 1).toBuilder()
                .methodId("unprojectable_method").build();
        FinanceMetricRecord recB = renderableRecord("rec-b", 2);
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(recA, recSkipped, recB));
        stubProjectorEcho();
        when(projector.project(argThat(in -> in != null && "unprojectable_method".equals(in.methodId()))))
                .thenReturn(Optional.empty());

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventService).appendOnce(eq(RUN_ID), eq(USER_ID),
                eq(FinanceResultComposer.EVENT_RESULT_BLOCK_RENDERED), anyString(), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload.get("finance.record.count")).isEqualTo(2);
        assertThat(payload.get("finance.record.ids")).isEqualTo(List.of("rec-a", "rec-b"));
        assertThat(result).doesNotContain("[rec-a, rec-b]");
    }

    @Test
    void append_shouldBlankEnvironmentIdInPayloadWhenRecordsSpanMultipleEnvironments() {
        FinanceMetricRecord recA = renderableRecord("rec-a", 0).toBuilder()
                .actualEnvironmentId("env-a").build();
        FinanceMetricRecord recB = renderableRecord("rec-b", 1).toBuilder()
                .actualEnvironmentId("env-b").build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(recA, recB));
        stubProjectorEcho();

        composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventService).appendOnce(eq(RUN_ID), eq(USER_ID),
                eq(FinanceResultComposer.EVENT_RESULT_BLOCK_RENDERED), anyString(), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload.get("finance.environment.id")).isEqualTo("");
    }

    // ========== denylist：内部身份/事件信息永不进入 Markdown ==========

    @Test
    void append_shouldExcludeInternalIdentityTokensFromMarkdown() {
        FinanceMetricRecord record = renderableRecord("rec-SECRET-RECORD", 0).toBuilder()
                .methodVersion("9.9.9-INTERNAL")
                .specDigest("deadbeefcafe1234")
                .rawDigest("RAW-DIGEST-MARKER")
                .sourceResolverToolCallId("resolver-SECRET-CALL")
                .actualEnvironmentId("env-SECRET")
                .parametersJson("{\"secret_param\":\"PARAM-MARKER\"}")
                .build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(record));
        when(resolutionQuery.findExact(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(FinanceMethodResolution.builder()
                        .targetEnvironmentId("env-OTHER-SECRET").build());
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        String markdown = result.substring("说明".length());
        assertThat(markdown).doesNotContain("rec-SECRET-RECORD");
        assertThat(markdown).doesNotContain("9.9.9-INTERNAL");
        assertThat(markdown).doesNotContain("deadbeefcafe1234");
        assertThat(markdown).doesNotContain("RAW-DIGEST-MARKER");
        assertThat(markdown).doesNotContain("resolver-SECRET-CALL");
        assertThat(markdown).doesNotContain("env-SECRET");
        assertThat(markdown).doesNotContain("env-OTHER-SECRET");
        assertThat(markdown).doesNotContain("PARAM-MARKER");
        assertThat(markdown).doesNotContain(FinanceResultBlockRenderer.RENDERER_VERSION);
        assertThat(markdown).doesNotContain("CROSS_ENVIRONMENT");
        // codex e740f454 ③ 验收硬词：最终用户字符串/三列表格不得出现以下身份形状
        assertHardWordsAbsent(result);
    }

    // ========== cell denylist（codex 935bef41 P0）：投影 cell 命中后台身份 token 即跳过 ==========

    @Test
    void append_shouldSkipRecordWhenCustomFormulaContainsIdentityTokens_customWithChecks() {
        FinanceMetricRecord injection = renderableRecord("rec-inject-1", 0).toBuilder()
                .declaredEvidence("CUSTOM_WITH_CHECKS")
                .formulaDescription("sha256:abc123 environmentId=env-x sourceResolverToolCallId=call-9")
                .build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(injection));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "原文");

        assertThat(result).isEqualTo("原文");
        assertThat(result).doesNotContain("sha256:abc123");
        assertThat(result).doesNotContain("environmentId");
        assertThat(result).doesNotContain("sourceResolverToolCallId");
        verifyNoInteractions(eventService);
    }

    @Test
    void append_shouldSkipRecordWhenCustomFormulaContainsIdentityTokens_customUnverified() {
        FinanceMetricRecord injection = renderableRecord("rec-inject-2", 0).toBuilder()
                .declaredEvidence("CUSTOM_UNVERIFIED")
                .formulaDescription("methodVersion=9.9 specDigest=deadbeef imageDigest=img packageApis=[x]")
                .build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(injection));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "原文");

        assertThat(result).isEqualTo("原文");
        verifyNoInteractions(eventService);
    }

    @Test
    void append_shouldSkipOnlyInjectedRecordAndStillRenderCleanOnes() {
        FinanceMetricRecord injected = renderableRecord("rec-inject-3", 0).toBuilder()
                .declaredEvidence("CUSTOM_WITH_CHECKS")
                .formulaDescription("batchId=b-1 blockId=bl-2 inputRefs=[r] executePythonToolCallId=tc-1")
                .build();
        FinanceMetricRecord clean = renderableRecord("rec-clean", 1);
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(injected, clean));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        assertThat(result).contains("M:tushare_index_daily");
        assertThat(result).doesNotContain("batchId");
        assertThat(result).doesNotContain("blockId");
        assertThat(result).doesNotContain("inputRefs");
        assertThat(result).doesNotContain("executePythonToolCallId");
        // 干净记录仍渲染：事件 record.ids 只含 rec-clean
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventService).appendOnce(eq(RUN_ID), eq(USER_ID),
                eq(FinanceResultComposer.EVENT_RESULT_BLOCK_RENDERED), anyString(), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload.get("finance.record.ids")).isEqualTo(List.of("rec-clean"));
    }

    @Test
    void append_shouldRenderLegitimateNaturalLanguageCustomDescription() {
        FinanceMetricRecord legit = renderableRecord("rec-legit", 0).toBuilder()
                .declaredEvidence("CUSTOM_UNVERIFIED")
                .formulaDescription("按 (结束值 / 起始值)^(1 / 区间长度) - 1 计算复合增长，再按年化口径折算")
                .build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(legit));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "");

        assertThat(result).contains("H:按 (结束值 / 起始值)^(1 / 区间长度) - 1 计算复合增长，再按年化口径折算");
    }

    @Test
    void cellDenylist_shouldPinEveryIdentityCategory() {
        // codex 935bef41 类别钉死：digest/environment/image/package/version/evidence/
        // record/batch/block/task/dataset/toolCall/sourceResolver/run 等身份形状全部命中
        List<String> injections = List.of(
                "sha256:0123abcd",
                "methodId=x", "methodVersion=1", "specDigest=d", "rawDigest=r", "recordDigest=rd",
                "recordId=r", "recordIndex=1", "inputRefs=[]",
                "runId=r", "todoId=t", "taskId=t", "batchId=b", "blockId=b", "datasetId=d",
                "environmentId=e", "actualEnvironmentId=e", "targetEnvironmentId=e",
                "imageDigest=i", "imageRef=i", "imageId=i", "runtimeImage=r",
                "librarySetDigest=l", "catalogDigest=c",
                "packageApis=[]", "packageName=p", "packageVersion=1", "apiCompatRange=a", "apiVersion=1",
                "rendererVersion=1", "resolverPromptVersion=1", "resolverSchemaVersion=1",
                "declaredEvidence=x", "effectiveInternalEvidence=x",
                "executePythonToolCallId=t", "toolCallId=t",
                "sourceResolverToolCallId=s", "resolverToolCallId=s", "sourceResolver=s",
                "LIBRARY_CALL_DECLARED", "CUSTOM_WITH_CHECKS", "CUSTOM_UNVERIFIED",
                "FINANCE_RESULT_BLOCK_RENDERED", "FINANCE_CROSS_ENVIRONMENT",
                "finance.block.id", "finance.record.id", "finance.environment.id"
        );
        for (String injection : injections) {
            assertThat(FinanceResultComposer.containsDenylistedToken("正常说明", "1.0", injection))
                    .as("injection should be denylisted: %s", injection)
                    .isTrue();
        }
        // codex 52799ba0：通用类别词裸形 exact bypass 钉死（不带 Id/Version/Digest 复合形状也拦）
        List<String> bareCategoryBypasses = List.of(
                "digest=deadbeef", "environment=prod", "image=runtime", "package=numpy",
                "version=9", "evidence=custom", "record=x", "batch=x", "block=x",
                "task=x", "dataset=x", "toolCall=x"
        );
        for (String bypass : bareCategoryBypasses) {
            assertThat(FinanceResultComposer.containsDenylistedToken(bypass))
                    .as("bare category bypass should be denylisted: %s", bypass)
                    .isTrue();
        }
    }

    @Test
    void cellDenylist_shouldCatchSnakeKebabAndSpacedSynonymForms() {
        // codex b4a4d737：camel/snake/kebab/分词同义键不得绕过 compact 口径
        List<String> synonyms = List.of(
                "sourceResolverToolCallId=1", "source_resolver_tool_call_id=1",
                "source-resolver-tool-call-id=1", "source resolver tool call id: 1",
                "environmentId=e", "environment_id=e", "environment-id=e",
                "record_id=r", "record-id=r",
                "package_version=1", "package-version=1",
                "apiVersion=2", "api_version=2",
                "imageId=i", "image_id=i", "runtime_image=r", "runtime-image=r",
                "SHA256:ABCD", "前缀 sha256: 值"
        );
        for (String synonym : synonyms) {
            assertThat(FinanceResultComposer.containsDenylistedToken(synonym))
                    .as("synonym form should be denylisted: %s", synonym)
                    .isTrue();
        }
        // 合法自然语言正对照（codex b4a4d737 要求保留）
        assertThat(FinanceResultComposer.containsDenylistedToken(
                "复合年均增长率", "12.34%", "按 canonical 公式计算年化收益")).isFalse();
        assertThat(FinanceResultComposer.containsDenylistedToken(
                "按收盘价序列的一阶差分计算日收益，再年化")).isFalse();
        assertThat(FinanceResultComposer.containsDenylistedToken(null, "", "  ")).isFalse();
    }

    private static void assertHardWordsAbsent(String finalText) {
        List<String> hardWords = List.of(
                "sha256:",
                "LIBRARY_CALL_DECLARED", "CUSTOM_WITH_CHECKS", "CUSTOM_UNVERIFIED",
                "inputRefs", "executePythonToolCallId", "sourceResolverToolCallId",
                "batchId", "blockId", "environmentId", "methodVersion", "specDigest",
                "packageApis", "recordId", "runId", "taskId", "datasetId",
                "rendererVersion", "FINANCE_RESULT_BLOCK_RENDERED", "FINANCE_CROSS_ENVIRONMENT"
        );
        for (String word : hardWords) {
            assertThat(finalText).as("final text must not contain hard word: %s", word)
                    .doesNotContain(word);
        }
    }

    // ========== 显示格式 ==========

    @Test
    void append_shouldFormatPercentWhenSpecDisplayFormatIsPercent() {
        FinanceMetricRecord record = renderableRecord("rec-1", 0).toBuilder()
                .valueJson("0.1234").build();
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(record));
        FinanceMethodSpec spec = FinanceMethodSpec.builder()
                .methodId("tushare_index_daily")
                .version("1.0.0")
                .specDigest("0123456789abcdef")
                .outputs(List.of(FinanceMethodSpec.FinanceOutput.builder()
                        .name("value").displayFormat("percent").build()))
                .build();
        when(specCatalog.find("tushare_index_daily", "1.0.0", "0123456789abcdef"))
                .thenReturn(Optional.of(spec));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "");

        assertThat(result).contains("12.34%");
    }

    // ========== 故障容错 ==========

    @Test
    void append_shouldReturnModelTextWhenRecordQueryThrows() {
        when(recordQuery.listRenderableByRun(RUN_ID)).thenThrow(new RuntimeException("db down"));

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "原文");

        assertThat(result).isEqualTo("原文");
    }

    @Test
    void append_shouldStillReturnComposedTextWhenEventAppendThrows() {
        FinanceMetricRecord record = renderableRecord("rec-1", 0);
        when(recordQuery.listRenderableByRun(RUN_ID)).thenReturn(List.of(record));
        when(eventService.appendOnce(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("event store down"));
        stubProjectorEcho();

        String result = composer.appendFinanceResultBlock(RUN_ID, USER_ID, "说明");

        assertThat(result).startsWith("说明\n\n| 方法 | 结果 | 如何计算 |");
    }

    // ========== 辅助 ==========

    private FinanceMetricRecord renderableRecord(String recordId, Integer recordIndex) {
        return FinanceMetricRecord.builder()
                .recordId(recordId)
                .runId(RUN_ID)
                .todoId("todo-1")
                .executePythonToolCallId("exec-call-1")
                .recordIndex(recordIndex)
                .sourceResolverToolCallId("resolver-call-1")
                .methodId("tushare_index_daily")
                .methodVersion("1.0.0")
                .specDigest("0123456789abcdef")
                .valueJson("123.45")
                .unit("CNY")
                .parametersJson("{\"ts_code\":\"000300.SH\"}")
                .formulaDescription("收盘价均值")
                .declaredEvidence("LIBRARY_CALL_DECLARED")
                .renderable(true)
                .build();
    }

    /** echo 投影：method/howCalculated 带前缀，便于按记录区分行；value 原样透传。 */
    private void stubProjectorEcho() {
        lenient().when(projector.project(any())).thenAnswer(invocation -> {
            FinanceResultModelProjector.FinanceResultProjectionInput in = invocation.getArgument(0);
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(new FinanceResultModelProjector.FinanceResultProjection(
                    "M:" + in.methodId(), in.value(), in.unit(), "H:" + in.formulaDescription()));
        });
    }
}
