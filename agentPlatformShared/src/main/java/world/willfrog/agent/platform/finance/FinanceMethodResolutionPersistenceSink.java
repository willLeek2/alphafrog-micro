package world.willfrog.agent.platform.finance;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Bridges the resolver SPI to the transactional method-resolution persister. */
@Component
public class FinanceMethodResolutionPersistenceSink implements FinanceMethodResolutionSink {
    private static final String SAVE_FAILED_MESSAGE =
            "Unable to persist finance method resolution snapshots";

    private final FinanceMethodResolutionPersister persister;

    public FinanceMethodResolutionPersistenceSink(FinanceMethodResolutionPersister persister) {
        this.persister = persister;
    }

    @Override
    public void saveAll(List<FinanceMethodResolutionSnapshot> snapshots) {
        if (snapshots == null) {
            throw new FinanceMethodResolutionSinkException(
                    SAVE_FAILED_MESSAGE,
                    new IllegalArgumentException("snapshots must not be null"));
        }
        try {
            List<FinanceMethodResolution> rows = new ArrayList<>(snapshots.size());
            for (FinanceMethodResolutionSnapshot snapshot : snapshots) {
                rows.add(toRow(snapshot));
            }
            // persistBatch owns the single transaction and canonical content digest.
            persister.persistBatch(rows);
        } catch (FinanceMethodResolutionSinkException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FinanceMethodResolutionSinkException(SAVE_FAILED_MESSAGE, exception);
        }
    }

    private static FinanceMethodResolution toRow(FinanceMethodResolutionSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        OffsetDateTime createdAt = snapshot.createdAt() == null
                ? null
                : OffsetDateTime.ofInstant(snapshot.createdAt(), ZoneOffset.UTC);
        return FinanceMethodResolution.builder()
                .runId(snapshot.runId())
                .resolverToolCallId(snapshot.resolverToolCallId())
                .todoId(snapshot.todoId())
                .methodId(snapshot.methodId())
                .methodVersion(snapshot.methodVersion())
                .specDigest(snapshot.specDigest())
                .catalogDigest(snapshot.catalogDigest())
                .resolverSchemaVersion(snapshot.resolverSchemaVersion())
                .resolverPromptVersion(snapshot.resolverPromptVersion())
                .modelRouteJson(snapshot.modelRouteJson())
                .matchReason(snapshot.matchReason())
                .clarificationJson(snapshot.clarificationJson())
                .targetEnvironmentId(snapshot.targetEnvironmentId())
                .targetPackageApiJson(snapshot.targetPackageApiJson())
                .resolutionPayloadJson(snapshot.resolutionPayloadJson())
                // Deliberately ignore snapshot.resolutionContentDigest(); the persister recomputes it.
                .resolutionContentDigest(null)
                .createdAt(createdAt)
                .build();
    }
}
