package world.willfrog.agent.platform.finance;

import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.mapper.FinanceMetricRecordMapper;

import java.util.List;

/** Read-only backend query used by the server-side result composer. */
@Component
public class FinanceRecordQuery {
    private final FinanceMetricRecordMapper mapper;

    public FinanceRecordQuery(FinanceMetricRecordMapper mapper) {
        this.mapper = mapper;
    }

    public List<FinanceMetricRecord> listRenderableByRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return List.copyOf(mapper.listByRunRenderable(runId));
    }

    public List<FinanceMetricRecord> listByBatch(
            String runId,
            String todoId,
            String executePythonToolCallId) {
        if (blank(runId) || blank(todoId) || blank(executePythonToolCallId)) {
            return List.of();
        }
        return List.copyOf(mapper.listByBatch(runId, todoId, executePythonToolCallId));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
