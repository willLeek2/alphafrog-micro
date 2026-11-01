package world.willfrog.agentlangchain.tooljob;

import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaitMemberDurableRequestResolverTest {
    private static final String OPERATION = "run:call:1";
    private static final String FINGERPRINT = "sha256:fingerprint";





    private static WaitMemberDispatchProof proof() throws Exception {
        ExecuteRequest request = ExecuteRequest.newBuilder()
                .setOperationId(OPERATION).setRequestFingerprint(FINGERPRINT)
                .setCode("print(1)").build();
        return new WaitMemberDispatchProof(
                WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION, OPERATION, null,
                FINGERPRINT, "{}", "{}", "{}", "2026-09-29T00:00:00Z",
                JsonFormat.printer().omittingInsignificantWhitespace().print(request));
    }
}
