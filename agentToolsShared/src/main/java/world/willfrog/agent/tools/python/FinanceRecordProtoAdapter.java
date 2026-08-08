package world.willfrog.agent.tools.python;

import world.willfrog.agent.platform.finance.FinanceEnvironmentFact;
import world.willfrog.agent.platform.finance.FinanceRecordChannelMetadata;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxEnvironmentIdentity;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxPackageApi;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import java.util.ArrayList;
import java.util.List;

/** Presence-preserving typed projection from Sandbox proto fields 10/11 to the shared processor DTOs. */
public final class FinanceRecordProtoAdapter {

    private FinanceRecordProtoAdapter() {
    }

    public static FinanceRecordChannelMetadata channelMetadata(TaskResultResponse response) {
        if (response == null || !response.hasFinanceRecordChannel()) {
            return null;
        }
        world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata source =
                response.getFinanceRecordChannel();
        return new FinanceRecordChannelMetadata(
                source.getEmittedRecordCount(),
                source.getEmittedRecordBytes(),
                source.getRecordSetComplete(),
                source.getDropReason(),
                source.getRecordDigest(),
                source.getStdoutTruncated(),
                source.getStderrTruncated());
    }

    public static FinanceEnvironmentFact executionEnvironment(TaskResultResponse response) {
        if (response == null || !response.hasExecutionEnvironment()) {
            return null;
        }
        SandboxEnvironmentIdentity source = response.getExecutionEnvironment();
        List<FinanceEnvironmentFact.PackageApi> packageApis = new ArrayList<>();
        for (SandboxPackageApi packageApi : source.getPackageApisList()) {
            packageApis.add(new FinanceEnvironmentFact.PackageApi(
                    packageApi.getName(), packageApi.getVersion(), packageApi.getApiVersion()));
        }
        return new FinanceEnvironmentFact(
                source.getEnvironmentId(),
                source.getImageDigest(),
                source.getLibrarySetDigest(),
                packageApis,
                source.getInventoryComplete());
    }
}
