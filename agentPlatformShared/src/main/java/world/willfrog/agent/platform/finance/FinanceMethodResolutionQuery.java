package world.willfrog.agent.platform.finance;

import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.mapper.FinanceMethodResolutionMapper;

import java.util.List;

/** Trusted run-scoped resolver snapshot lookups. */
@Component
public class FinanceMethodResolutionQuery {
    private final FinanceMethodResolutionMapper mapper;

    public FinanceMethodResolutionQuery(FinanceMethodResolutionMapper mapper) {
        this.mapper = mapper;
    }

    public FinanceMethodResolution findExact(
            String runId,
            String resolverToolCallId,
            String methodId,
            String methodVersion,
            String specDigest) {
        if (blank(runId) || blank(resolverToolCallId) || blank(methodId)
                || blank(methodVersion) || blank(specDigest)) {
            return null;
        }
        return mapper.findExact(runId, resolverToolCallId, methodId, methodVersion, specDigest);
    }

    public List<FinanceMethodResolution> listByRun(String runId) {
        return blank(runId) ? List.of() : List.copyOf(mapper.listByRun(runId));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
