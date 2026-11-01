package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.FinanceMethodResolutionMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FinanceMethodResolutionPersisterTest {
    private final FinanceMethodResolutionMapper mapper = mock(FinanceMethodResolutionMapper.class);
    private final FinanceMethodResolutionPersister persister =
            new FinanceMethodResolutionPersister(mapper, new ObjectMapper());






    private static FinanceMethodResolution resolution(String reason) {
        return FinanceMethodResolution.builder()
                .runId("run-1").resolverToolCallId("resolver-1").todoId("todo-1")
                .methodId("finance.growth.cagr").methodVersion("1.0.0")
                .specDigest("sha256:spec").catalogDigest("sha256:catalog")
                .resolverSchemaVersion("1").resolverPromptVersion("1")
                .modelRouteJson("{\"model\":\"light\"}").matchReason(reason)
                .clarificationJson("[]").targetEnvironmentId("sha256:target")
                .targetPackageApiJson("[]").resolutionPayloadJson("{\"status\":\"MATCHED\"}")
                .build();
    }
}
