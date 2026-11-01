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








    private FinanceRecordExtractionResult extraction(
            FinanceMetricRecord record,
            List<FinanceRecordExtractionResult.ModelNotice> notices) {
        return new FinanceRecordExtractionResult(
                null, List.of(record), "rows=5", "", notices, true);
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
