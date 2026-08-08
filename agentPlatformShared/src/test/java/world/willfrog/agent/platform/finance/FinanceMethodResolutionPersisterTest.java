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

    @Test
    void computesContentDigestAndAcceptsExactReplay() {
        FinanceMethodResolution row = resolution("reason-a");
        when(mapper.insertIgnore(row)).thenReturn(1, 0);
        when(mapper.findExact("run-1", "resolver-1", "finance.growth.cagr", "1.0.0", "sha256:spec"))
                .thenReturn(row);

        persister.persistBatch(List.of(row));
        String digest = row.getResolutionContentDigest();
        persister.persistBatch(List.of(row));

        assertThat(digest).hasSize(64);
        assertThat(row.getResolutionContentDigest()).isEqualTo(digest);
        verify(mapper, times(2)).insertIgnore(row);
    }

    @Test
    void sameIdentityDifferentContentIsConflict() {
        FinanceMethodResolution requested = resolution("reason-new");
        FinanceMethodResolution existing = resolution("reason-old");
        existing.setResolutionContentDigest("0".repeat(64));
        when(mapper.insertIgnore(any())).thenReturn(0);
        when(mapper.findExact(any(), any(), any(), any(), any())).thenReturn(existing);

        assertThatThrownBy(() -> persister.persistBatch(List.of(requested)))
                .isInstanceOf(FinanceRecordProcessingException.class)
                .extracting("code")
                .isEqualTo("FINANCE_RESOLUTION_IDENTITY_CONFLICT");
    }

    @Test
    void mixedResolverInvocationCannotBePartiallyPersisted() {
        FinanceMethodResolution first = resolution("reason-a");
        FinanceMethodResolution second = resolution("reason-b");
        second.setResolverToolCallId("resolver-other");

        assertThatThrownBy(() -> persister.persistBatch(List.of(first, second)))
                .isInstanceOf(FinanceRecordProcessingException.class)
                .extracting("code")
                .isEqualTo("FINANCE_RESOLUTION_BATCH_IDENTITY_INVALID");
        verifyNoInteractions(mapper);
    }

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
