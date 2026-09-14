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

    @Test
    void mapsTheSnapshotAllowlistAndDelegatesTheWholeBatchOnce() {
        Instant createdAt = Instant.parse("2026-08-09T00:00:00Z");
        FinanceMethodResolutionSnapshot first = snapshot(
                "finance.growth.cagr", "caller-controlled-digest", createdAt);
        FinanceMethodResolutionSnapshot second = snapshot(
                "finance.risk.sharpe_ratio", "other-caller-digest", createdAt.plusSeconds(1));

        sink.saveAll(List.of(first, second));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FinanceMethodResolution>> captor = ArgumentCaptor.forClass(List.class);
        verify(persister).persistBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        FinanceMethodResolution row = captor.getValue().get(0);
        assertThat(row.getRunId()).isEqualTo("run-1");
        assertThat(row.getResolverToolCallId()).isEqualTo("resolver-call-1");
        assertThat(row.getTodoId()).isEqualTo("todo-1");
        assertThat(row.getMethodId()).isEqualTo("finance.growth.cagr");
        assertThat(row.getMethodVersion()).isEqualTo("1.0.0");
        assertThat(row.getSpecDigest()).isEqualTo("sha256:spec");
        assertThat(row.getCatalogDigest()).isEqualTo("sha256:catalog");
        assertThat(row.getResolverSchemaVersion()).isEqualTo("1");
        assertThat(row.getResolverPromptVersion()).isEqualTo("sha256:prompt");
        assertThat(row.getModelRouteJson()).isEqualTo("{\"model\":\"light\"}");
        assertThat(row.getMatchReason()).isEqualTo("matched");
        assertThat(row.getClarificationJson()).isEqualTo("[]");
        assertThat(row.getTargetEnvironmentId()).isEqualTo("sha256:target");
        assertThat(row.getTargetPackageApiJson()).isEqualTo("[]");
        assertThat(row.getResolutionPayloadJson()).isEqualTo("{\"status\":\"MATCHED\"}");
        assertThat(row.getResolutionContentDigest()).isNull();
        assertThat(row.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }

    @Test
    void wrapsPersisterFailuresInTheResolverSpiException() {
        FinanceRecordProcessingException cause = new FinanceRecordProcessingException(
                "FINANCE_RESOLUTION_IDENTITY_CONFLICT", "conflict");
        doThrow(cause).when(persister).persistBatch(org.mockito.ArgumentMatchers.anyList());

        assertThatThrownBy(() -> sink.saveAll(List.of(snapshot(
                "finance.growth.cagr", "untrusted", Instant.EPOCH))))
                .isInstanceOf(FinanceMethodResolutionSinkException.class)
                .hasMessage("Unable to persist finance method resolution snapshots")
                .hasCause(cause);
    }

    @Test
    void realPersisterRecomputesTheCallerDigestBeforeInsert() {
        FinanceMethodResolutionMapper mapper = mock(FinanceMethodResolutionMapper.class);
        when(mapper.insertIgnore(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        FinanceMethodResolutionPersistenceSink realSink =
                new FinanceMethodResolutionPersistenceSink(
                        new FinanceMethodResolutionPersister(mapper, new ObjectMapper()));

        realSink.saveAll(List.of(snapshot(
                "finance.growth.cagr", "caller-controlled-digest", Instant.EPOCH)));

        ArgumentCaptor<FinanceMethodResolution> captor =
                ArgumentCaptor.forClass(FinanceMethodResolution.class);
        verify(mapper).insertIgnore(captor.capture());
        assertThat(captor.getValue().getResolutionContentDigest())
                .hasSize(64)
                .isNotEqualTo("caller-controlled-digest");
    }

    @Test
    void springExposesExactlyOneResolverSinkBean() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    FinanceMethodResolutionPersister.class,
                    () -> mock(FinanceMethodResolutionPersister.class));
            context.register(FinanceMethodResolutionPersistenceSink.class);
            context.refresh();

            assertThat(context.getBeansOfType(FinanceMethodResolutionSink.class))
                    .containsOnlyKeys("financeMethodResolutionPersistenceSink");
        }
    }

    @Test
    void nullBatchAndNullSnapshotFailBeforePersistence() {
        assertThatThrownBy(() -> sink.saveAll(null))
                .isInstanceOf(FinanceMethodResolutionSinkException.class);
        assertThatThrownBy(() -> sink.saveAll(java.util.Collections.singletonList(null)))
                .isInstanceOf(FinanceMethodResolutionSinkException.class);
        verifyNoInteractions(persister);
    }

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
