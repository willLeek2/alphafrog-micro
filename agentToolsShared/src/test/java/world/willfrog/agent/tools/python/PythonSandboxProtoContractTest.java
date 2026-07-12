package world.willfrog.agent.tools.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxProto;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;

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
}
