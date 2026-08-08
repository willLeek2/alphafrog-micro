package world.willfrog.agent.tools.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxProto;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxEnvironmentIdentity;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxPackageApi;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

class PythonSandboxProtoContractTest {

    @Test
    void oldExecuteRequestRemainsValidWithAdditiveDefaults() {
        ExecuteRequest request = ExecuteRequest.newBuilder()
                .setDatasetId("dataset-1")
                .setCode("print('ok')")
                .build();

        assertEquals("", request.getOperationId());
        assertEquals("", request.getRequestFingerprint());
        assertEquals(0L, request.getMemoryLimitBytes());
        assertEquals(0L, request.getTimeoutMillis());
    }

    @Test
    void usagePresenceDistinguishesMissingFromZero() {
        SandboxResourceUsage missing = SandboxResourceUsage.newBuilder()
                .setResourceClass("STANDARD")
                .setAttributionComplete(false)
                .addMissingFields("cpuMillis")
                .build();
        SandboxResourceUsage measuredZero = SandboxResourceUsage.newBuilder()
                .setResourceClass("STANDARD")
                .setCpuMillis(0L)
                .setAttributionComplete(true)
                .build();

        assertFalse(missing.hasCpuMillis());
        assertTrue(measuredZero.hasCpuMillis());
        assertEquals(0L, measuredZero.getCpuMillis());
    }

    @Test
    void partialUsageCarriesOnlyStableP0MissingNames() {
        SandboxResourceUsage loaderPartial = SandboxResourceUsage.newBuilder()
                .setResourceClass("STANDARD")
                .setAttributionComplete(false)
                .addMissingFields("logicalBytesScanned")
                .addMissingFields("datasetOpenCount")
                .build();
        SandboxResourceUsage measuredZero = SandboxResourceUsage.newBuilder()
                .setResourceClass("STANDARD")
                .setLogicalBytesScanned(0L)
                .setDatasetOpenCount(0)
                .setAttributionComplete(true)
                .build();

        assertFalse(loaderPartial.hasLogicalBytesScanned());
        assertFalse(loaderPartial.hasDatasetOpenCount());
        assertEquals(
                List.of("logicalBytesScanned", "datasetOpenCount"),
                loaderPartial.getMissingFieldsList());
        assertTrue(measuredZero.hasLogicalBytesScanned());
        assertTrue(measuredZero.hasDatasetOpenCount());
        assertEquals(0L, measuredZero.getLogicalBytesScanned());
        assertEquals(0, measuredZero.getDatasetOpenCount());
    }

    @Test
    void operationLookupRpcAndMessagesAreGenerated() {
        GetTaskByOperationIdRequest request = GetTaskByOperationIdRequest.newBuilder()
                .setOperationId("run-1:call-1:1")
                .build();

        assertEquals("run-1:call-1:1", request.getOperationId());
        assertNotNull(PythonSandboxProto.getDescriptor()
                .findServiceByName("PythonSandboxService")
                .findMethodByName("getTaskByOperationId"));
    }

    @Test
    void financeRecordChannelParentPresenceDistinguishesOldProducerFromV5() {
        // 260808-finance-methodspec-v5: f1-f9 unchanged. Parent absence means old
        // producer (no v5 protocol); parent present means v5 enabled. Mixing
        // proto3 defaults with v5 must be rejected, not silently accepted.
        TaskResultResponse oldProducer = TaskResultResponse.newBuilder()
                .setTaskId("t-1")
                .setStatus("SUCCEEDED")
                .setExitCode(0)
                .build();

        assertFalse(oldProducer.hasFinanceRecordChannel());
        assertFalse(oldProducer.hasExecutionEnvironment());

        TaskResultResponse v5Enabled = TaskResultResponse.newBuilder()
                .setTaskId("t-2")
                .setStatus("SUCCEEDED")
                .setExitCode(0)
                .setFinanceRecordChannel(FinanceRecordChannelMetadata.newBuilder()
                        .setEmittedRecordCount(0)
                        .setEmittedRecordBytes(0L)
                        .setRecordSetComplete(true)
                        .setRecordDigest("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                        .build())
                .build();

        assertTrue(v5Enabled.hasFinanceRecordChannel());
        assertFalse(v5Enabled.hasExecutionEnvironment());
        assertEquals(0, v5Enabled.getFinanceRecordChannel().getEmittedRecordCount());
        assertEquals(0L, v5Enabled.getFinanceRecordChannel().getEmittedRecordBytes());
        assertTrue(v5Enabled.getFinanceRecordChannel().getRecordSetComplete());
    }

    @Test
    void executionEnvironmentPresenceIncludesPackageApiList() {
        // 260808-finance-methodspec-v5: SandboxEnvironmentIdentity must surface
        // both environmentId / imageDigest / librarySetDigest scalars and the
        // repeated SandboxPackageApi snapshot. The contract requires no
        // runtimeImageRef field on either identity or package.
        TaskResultResponse result = TaskResultResponse.newBuilder()
                .setTaskId("t-3")
                .setStatus("SUCCEEDED")
                .setExitCode(0)
                .setExecutionEnvironment(SandboxEnvironmentIdentity.newBuilder()
                        .setEnvironmentId("sha256:actual-runtime-example")
                        .setImageDigest("sha256:image-example")
                        .setLibrarySetDigest("sha256:library-set-example")
                        .setInventoryComplete(true)
                        .addPackageApis(SandboxPackageApi.newBuilder()
                                .setName("alphafrog_finance")
                                .setVersion("1.0.3")
                                .setApiVersion("1.0")
                                .build())
                        .build())
                .build();

        assertTrue(result.hasExecutionEnvironment());
        SandboxEnvironmentIdentity env = result.getExecutionEnvironment();
        assertEquals("sha256:actual-runtime-example", env.getEnvironmentId());
        assertEquals("sha256:image-example", env.getImageDigest());
        assertEquals("sha256:library-set-example", env.getLibrarySetDigest());
        assertTrue(env.getInventoryComplete());
        assertEquals(1, env.getPackageApisCount());

        SandboxPackageApi pkg = env.getPackageApis(0);
        assertEquals("alphafrog_finance", pkg.getName());
        assertEquals("1.0.3", pkg.getVersion());
        assertEquals("1.0", pkg.getApiVersion());
    }

    @Test
    void retryableFieldNumberIsUnchangedAndCousinsDoNotCollide() {
        // 260808-finance-methodspec-v5: retryable=9 must stay unchanged.
        // Fields 10 and 11 are new sibling messages and must not regress
        // retryable's tag, type, or default behavior.
        TaskResultResponse result = TaskResultResponse.newBuilder()
                .setTaskId("t-4")
                .setStatus("SUCCEEDED")
                .setExitCode(0)
                .setRetryable(true)
                .build();

        assertTrue(result.hasRetryable());
        assertTrue(result.getRetryable());
        assertFalse(result.hasFinanceRecordChannel());
        assertFalse(result.hasExecutionEnvironment());
    }
}
