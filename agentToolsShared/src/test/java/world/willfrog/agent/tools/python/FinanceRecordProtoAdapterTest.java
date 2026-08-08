package world.willfrog.agent.tools.python;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.finance.FinanceEnvironmentFact;
import world.willfrog.agent.platform.finance.FinanceRecordChannelMetadata;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxEnvironmentIdentity;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxPackageApi;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceRecordProtoAdapterTest {

    @Test
    void absentParentMessagesRemainAbsent() {
        TaskResultResponse response = TaskResultResponse.newBuilder().build();

        assertThat(FinanceRecordProtoAdapter.channelMetadata(response)).isNull();
        assertThat(FinanceRecordProtoAdapter.executionEnvironment(response)).isNull();
    }

    @Test
    void projectsEveryFrozenFieldWithoutInventingDefaults() {
        TaskResultResponse response = TaskResultResponse.newBuilder()
                .setFinanceRecordChannel(
                        world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata
                                .newBuilder()
                                .setEmittedRecordCount(2)
                                .setEmittedRecordBytes(759)
                                .setRecordSetComplete(true)
                                .setDropReason("none")
                                .setRecordDigest("sha256:batch")
                                .setStdoutTruncated(true)
                                .setStderrTruncated(false)
                                .build())
                .setExecutionEnvironment(SandboxEnvironmentIdentity.newBuilder()
                        .setEnvironmentId("sha256:environment")
                        .setImageDigest("sha256:image")
                        .setLibrarySetDigest("sha256:library")
                        .addPackageApis(SandboxPackageApi.newBuilder()
                                .setName("alphafrog_finance")
                                .setVersion("1.0.3")
                                .setApiVersion("1.0")
                                .build())
                        .setInventoryComplete(true)
                        .build())
                .build();

        assertThat(FinanceRecordProtoAdapter.channelMetadata(response)).isEqualTo(
                new FinanceRecordChannelMetadata(
                        2, 759, true, "none", "sha256:batch", true, false));
        assertThat(FinanceRecordProtoAdapter.executionEnvironment(response)).isEqualTo(
                new FinanceEnvironmentFact(
                        "sha256:environment",
                        "sha256:image",
                        "sha256:library",
                        java.util.List.of(new FinanceEnvironmentFact.PackageApi(
                                "alphafrog_finance", "1.0.3", "1.0")),
                        true));
    }
}
