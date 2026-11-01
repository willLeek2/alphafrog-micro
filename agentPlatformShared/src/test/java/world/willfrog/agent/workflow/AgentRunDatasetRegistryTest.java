package world.willfrog.agent.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.DatasetPersistedEvent.PersistedArtifactType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunDatasetRegistryTest {

    private AgentRunDatasetRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentRunDatasetRegistry();
    }

    private DatasetPersistedEvent datasetEvent(String runId, String datasetId, String sortKey) {
        return new DatasetPersistedEvent(this, runId, datasetId,
                "/data/database_fetched/domestic_listed_asset/600000.SH/" + datasetId + "/" + sortKey,
                "600000.SH", sortKey);
    }

    private DatasetPersistedEvent multiTsDatasetEvent(String runId, String datasetId, String sortKey, String fromTsCode) {
        return new DatasetPersistedEvent(this, runId, datasetId,
                "/data/database_fetched/domestic_listed_asset/" + datasetId + "/" + sortKey,
                fromTsCode, sortKey);
    }

    private DatasetPersistedEvent manifestEvent(String runId, String manifestId, String sortKey, List<String> related) {
        return new DatasetPersistedEvent(this, runId, manifestId,
                "/data/manifests/v1/manifest-" + manifestId + "/manifest.json",
                "UNCERTAIN", related, sortKey);
    }

































}
