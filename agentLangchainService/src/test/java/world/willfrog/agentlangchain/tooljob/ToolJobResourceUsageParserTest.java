package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ToolJobResourceUsageParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();










    private SandboxResourceUsage preCreateCanceledUsage() {
        return SandboxResourceUsage.newBuilder()
                .setResourceClass("UNKNOWN")
                .setExitReason("CANCELED")
                .setAttributionComplete(false)
                .addAllMissingFields(List.of(
                        "cpuMillis", "memoryPeakBytes", "memoryByteMillis",
                        "logicalBytesScanned", "artifactBytesWritten",
                        "temporaryBytesWritten", "queueWaitMillis", "prepareMillis",
                        "executionWallMillis", "cleanupMillis", "datasetOpenCount",
                        "samplingIntervalMillis"))
                .build();
    }

    private SandboxResourceUsage.Builder completeUsage() {
        return SandboxResourceUsage.newBuilder()
                .setResourceClass("STANDARD")
                .setCpuMillis(11)
                .setMemoryPeakBytes(22)
                .setLogicalBytesScanned(33)
                .setQueueWaitMillis(1)
                .setPrepareMillis(2)
                .setExecutionWallMillis(3)
                .setCleanupMillis(4)
                .setDatasetOpenCount(5)
                .setExitReason("SUCCESS")
                .setAttributionComplete(true)
                .setSamplingIntervalMillis(100);
    }

    private String json(SandboxResourceUsage usage) throws Exception {
        return JsonFormat.printer().omittingInsignificantWhitespace().print(usage);
    }
}
