package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.finance.FinanceMetricRecord;
import world.willfrog.agent.platform.finance.FinanceRecordExtractionResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinanceResultModelAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinanceResultModelProjector projector = mock(FinanceResultModelProjector.class);
    private final FinanceResultModelAdapter adapter =
            new FinanceResultModelAdapter(objectMapper, projector);

    @Test
    void projectsPersistedAllowlistFieldsThroughCanonicalProjector() {
        when(projector.project(any())).thenReturn(Optional.of(
                new FinanceResultModelProjector.FinanceResultProjection(
                        "复合增长率", 0.1246, "ratio", "按规范参数计算")));

        FinanceResultModelAdapter.ProjectionBatch result = adapter.project(extraction(record(
                "0.1246", "{\"periods\":4}", true), List.of()));

        assertThat(result.results()).singleElement().satisfies(projected -> {
            assertThat(projected.method()).isEqualTo("复合增长率");
            assertThat(projected.value()).isEqualTo(0.1246);
            assertThat(projected.unit()).isEqualTo("ratio");
            assertThat(projected.howCalculated()).isEqualTo("按规范参数计算");
        });
        assertThat(result.notices()).isEmpty();

        ArgumentCaptor<FinanceResultModelProjector.FinanceResultProjectionInput> input =
                ArgumentCaptor.forClass(
                        FinanceResultModelProjector.FinanceResultProjectionInput.class);
        verify(projector).project(input.capture());
        assertThat(input.getValue().methodId()).isEqualTo("finance.growth.cagr");
        assertThat(input.getValue().methodVersion()).isEqualTo("1.0.0");
        assertThat(input.getValue().specDigest()).isEqualTo("sha256:spec");
        assertThat(input.getValue().parameters()).containsEntry("periods", 4);
        assertThat(input.getValue().renderable()).isTrue();
        assertThat(input.getValue().declaredEvidence()).isEqualTo(
                FinanceResultModelProjector.FinanceDeclaredEvidence.LIBRARY_CALL_DECLARED);
    }

    @Test
    void canonicalProjectionFailureAddsOneActionableNotice() {
        when(projector.project(any())).thenReturn(Optional.empty());
        FinanceRecordExtractionResult.ModelNotice existing =
                new FinanceRecordExtractionResult.ModelNotice(
                        "EXISTING", "已有提示", "按已有动作处理");

        FinanceResultModelAdapter.ProjectionBatch result = adapter.project(
                extraction(record("1", "{}", true), List.of(existing)));

        assertThat(result.results()).isEmpty();
        assertThat(result.notices()).extracting(
                        FinanceRecordExtractionResult.ModelNotice::code)
                .containsExactly("EXISTING", "FINANCE_RESULT_REJECTED");
        assertThat(result.notices().get(1).action()).isNotBlank();
    }

    @Test
    void oneProjectionFailureRejectsTheWholeModelResultBatch() {
        when(projector.project(any()))
                .thenReturn(Optional.of(new FinanceResultModelProjector.FinanceResultProjection(
                        "复合增长率", 0.1, "ratio", "计算一")))
                .thenReturn(Optional.empty());

        FinanceResultModelAdapter.ProjectionBatch result = adapter.project(
                new FinanceRecordExtractionResult(
                        null,
                        List.of(
                                record("0.1", "{\"periods\":4}", true),
                                record("0.2", "{\"periods\":5}", true)),
                        "rows=2",
                        List.of(),
                        true));

        assertThat(result.results()).isEmpty();
        assertThat(result.notices()).extracting(
                        FinanceRecordExtractionResult.ModelNotice::code)
                .containsExactly("FINANCE_RESULT_REJECTED");
    }

    @Test
    void malformedPersistedJsonFailsClosedWithoutCallingProjector() {
        FinanceResultModelAdapter.ProjectionBatch result = adapter.project(
                extraction(record("not-json", "[]", true), List.of()));

        assertThat(result.results()).isEmpty();
        assertThat(result.notices()).extracting(
                        FinanceRecordExtractionResult.ModelNotice::code)
                .containsExactly("FINANCE_RESULT_REJECTED");
        verify(projector, never()).project(any());
    }

    @Test
    void declaredEvidenceIsMappedExactlyAndInternalDowngradeCannotChangeProjectionKind() {
        when(projector.project(any())).thenReturn(Optional.of(
                new FinanceResultModelProjector.FinanceResultProjection(
                        "复合增长率", 0.1246, "ratio", "按规范参数计算")));
        FinanceMetricRecord record = record("0.1246", "{\"periods\":4}", true)
                .toBuilder()
                .declaredEvidence("LIBRARY_CALL_DECLARED")
                .effectiveInternalEvidence("CUSTOM_UNVERIFIED")
                .build();

        adapter.project(extraction(record, List.of()));

        ArgumentCaptor<FinanceResultModelProjector.FinanceResultProjectionInput> input =
                ArgumentCaptor.forClass(
                        FinanceResultModelProjector.FinanceResultProjectionInput.class);
        verify(projector).project(input.capture());
        assertThat(input.getValue().declaredEvidence()).isEqualTo(
                FinanceResultModelProjector.FinanceDeclaredEvidence.LIBRARY_CALL_DECLARED);
    }

    @Test
    void unknownDeclaredEvidenceRejectsBatchWithoutCallingProjector() {
        FinanceMetricRecord record = record("0.1246", "{\"periods\":4}", true)
                .toBuilder()
                .declaredEvidence("MODEL_FORGED_EVIDENCE")
                .build();

        FinanceResultModelAdapter.ProjectionBatch result =
                adapter.project(extraction(record, List.of()));

        assertThat(result.results()).isEmpty();
        assertThat(result.notices()).extracting(
                        FinanceRecordExtractionResult.ModelNotice::code)
                .containsExactly("FINANCE_RESULT_REJECTED");
        verify(projector, never()).project(any());
    }

    @Test
    void nonRenderableAuditRowsAreIgnoredWithoutDuplicatingProcessorNotice() {
        FinanceRecordExtractionResult.ModelNotice rejected =
                new FinanceRecordExtractionResult.ModelNotice(
                        "FINANCE_RESULT_REJECTED", "未接收", "修正输入后重试");

        FinanceResultModelAdapter.ProjectionBatch result = adapter.project(
                extraction(record("1", "{}", false), List.of(rejected)));

        assertThat(result.results()).isEmpty();
        assertThat(result.notices()).containsExactly(rejected);
        verify(projector, never()).project(any());
    }

    private FinanceRecordExtractionResult extraction(
            FinanceMetricRecord record,
            List<FinanceRecordExtractionResult.ModelNotice> notices) {
        return new FinanceRecordExtractionResult(
                null, List.of(record), "rows=5", notices, true);
    }

    private FinanceMetricRecord record(
            String valueJson,
            String parametersJson,
            boolean renderable) {
        return FinanceMetricRecord.builder()
                .methodId("finance.growth.cagr")
                .methodVersion("1.0.0")
                .specDigest("sha256:spec")
                .valueJson(valueJson)
                .unit("ratio")
                .parametersJson(parametersJson)
                .formulaDescription(null)
                .declaredEvidence("LIBRARY_CALL_DECLARED")
                .renderable(renderable)
                .build();
    }
}
