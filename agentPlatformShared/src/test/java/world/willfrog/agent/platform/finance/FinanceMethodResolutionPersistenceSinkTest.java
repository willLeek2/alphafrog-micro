package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import world.willfrog.agent.platform.mapper.FinanceMethodResolutionMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FinanceMethodResolutionPersistenceSinkTest {
    private final FinanceMethodResolutionPersister persister =
            mock(FinanceMethodResolutionPersister.class);
    private final FinanceMethodResolutionPersistenceSink sink =
            new FinanceMethodResolutionPersistenceSink(persister);






    private static FinanceMethodResolutionSnapshot snapshot(
            String methodId,
            String resolutionContentDigest,
            Instant createdAt) {
        return new FinanceMethodResolutionSnapshot(
                "run-1",
                "resolver-call-1",
                "todo-1",
                methodId,
                "1.0.0",
                "sha256:spec",
                "sha256:catalog",
                "1",
                "sha256:prompt",
                "{\"model\":\"light\"}",
                "matched",
                "[]",
                "sha256:target",
                "[]",
                "{\"status\":\"MATCHED\"}",
                resolutionContentDigest,
                createdAt);
    }
}
