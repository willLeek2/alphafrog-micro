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




    // ========== 空/跳过路径 ==========








    // ========== 跨环境后台核对 ==========





    // ========== 稳定 blockId / 事件 ==========





    // ========== denylist：内部身份/事件信息永不进入 Markdown ==========


    // ========== cell denylist（codex 935bef41 P0）：投影 cell 命中后台身份 token 即跳过 ==========







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


    // ========== 显示格式：多输出规格的 output 键定位（与投影器同一套规则） ==========






    // ========== 故障容错 ==========



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

    /** 多输出规格：第一个输出 sharpe 无格式，第二个 maxDrawdown 为 percent，用于 output 键定位测试。 */
    private FinanceMethodSpec multiOutputSpec() {
        return FinanceMethodSpec.builder()
                .methodId("tushare_index_daily")
                .version("1.0.0")
                .specDigest("0123456789abcdef")
                .outputs(List.of(
                        FinanceMethodSpec.FinanceOutput.builder()
                                .name("sharpe").unit("ratio").build(),
                        FinanceMethodSpec.FinanceOutput.builder()
                                .name("maxDrawdown").unit("percent").displayFormat("percent").build()))
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
