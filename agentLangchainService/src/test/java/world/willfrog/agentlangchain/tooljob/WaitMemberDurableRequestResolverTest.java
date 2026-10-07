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

    @Test
    void aWorkspaceRefusalAfterAnAuthoritativeMissIsReturnedAsABusinessResult() throws Exception {
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        when(sandbox.getTaskByOperationId(any()))
                .thenReturn(GetTaskByOperationIdResponse.getDefaultInstance());
        when(sandbox.createTask(any())).thenReturn(ExecuteResponse.newBuilder()
                .setWorkspaceResult(WorkspaceResult.WORKSPACE_DIRTY).build());

        WaitMemberDurableRequestResolver.Resolution resolved =
                WaitMemberDurableRequestResolver.resolveOutcome(proof(), sandbox);

        assertThat(resolved.taskId()).isNull();
        assertThat(resolved.workspaceRefusalCode()).isEqualTo("WORKSPACE_DIRTY");
    }

    @Test
    void anIneligibleRunIsReturnedAsAWorkspaceRefusal() throws Exception {
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        when(sandbox.getTaskByOperationId(any()))
                .thenReturn(GetTaskByOperationIdResponse.getDefaultInstance());
        when(sandbox.createTask(any())).thenReturn(ExecuteResponse.newBuilder()
                .setWorkspaceResult(WorkspaceResult.WORKSPACE_RUN_INELIGIBLE).build());

        WaitMemberDurableRequestResolver.Resolution resolved =
                WaitMemberDurableRequestResolver.resolveOutcome(proof(), sandbox);

        assertThat(resolved.taskId()).isNull();
        assertThat(resolved.workspaceRefusalCode()).isEqualTo("WORKSPACE_RUN_INELIGIBLE");
    }

    @Test
    void anExistingTaskAfterRefusalWinsOverTheRefusal() throws Exception {
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        when(sandbox.getTaskByOperationId(any()))
                .thenReturn(GetTaskByOperationIdResponse.getDefaultInstance(),
                        GetTaskByOperationIdResponse.newBuilder().setFound(true)
                                .setTaskId("accepted-task")
                                .setRequestFingerprint(FINGERPRINT).build());
        when(sandbox.createTask(any())).thenReturn(ExecuteResponse.newBuilder()
                .setWorkspaceResult(WorkspaceResult.WORKSPACE_DIRTY).build());

        WaitMemberDurableRequestResolver.Resolution resolved =
                WaitMemberDurableRequestResolver.resolveOutcome(proof(), sandbox);

        assertThat(resolved.taskId()).isEqualTo("accepted-task");
        assertThat(resolved.workspaceRefusalCode()).isNull();
    }

    @Test
    void aSavedRefusalCannotReplayCreateAfterRestart() throws Exception {
        PythonSandboxService sandbox = mock(PythonSandboxService.class);

        WaitMemberDurableRequestResolver.Resolution resolved =
                WaitMemberDurableRequestResolver.resolveOutcome(
                        proof().withWorkspaceRefusal("WORKSPACE_DELETED"), sandbox);

        assertThat(resolved.workspaceRefusalCode()).isEqualTo("WORKSPACE_DELETED");
        verify(sandbox, never()).createTask(any());
        verify(sandbox, never()).getTaskByOperationId(any());
    }

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
