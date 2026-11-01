package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolJobResumeServiceTest {

    @Mock
    private ToolJobAnchorService anchorService;

    private final world.willfrog.agentlangchain.gateway.RunOwnershipGateway ownershipGateway =
            org.mockito.Mockito.mock(world.willfrog.agentlangchain.gateway.RunOwnershipGateway.class);

    @Mock
    private ToolJobRedisCache redisCache;

    @Mock
    private ToolJobResumeLauncher resumeLauncher;

    @Mock
    private ToolJobConfig config;

    private ToolJobResumeService resumeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(config.getLaunchingStaleSeconds()).thenReturn(120L);
        lenient().when(config.getResumeLauncherLeaseSeconds()).thenReturn(30L);
        resumeService = new ToolJobResumeService(
                anchorService, redisCache, config, objectMapper, "owner-a", ownershipGateway);
        setLauncher(resumeService, resumeLauncher);
    }

    private void setLauncher(ToolJobResumeService service, ToolJobResumeLauncher launcher) {
        try {
            var field = ToolJobResumeService.class.getDeclaredField("resumeLauncher");
            field.setAccessible(true);
            field.set(service, launcher);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- entry guards ----



    // ---- 260818 (grace round-2): 取消锚点不自动恢复，CONSUMED 清理不受门控影响 ----



    // ---- READY → LAUNCHING (first launch) ----





    // ---- 260818 (grace round-3): 回滚 CAS 输掉（如取消先落库）后无后续启动副作用 ----



    // ---- LAUNCHING reentry (crash recovery, §9.11) ----



    // ---- CONSUMED and cleanup ----





    // ---- double-claim prevention (§9.11 token+version CAS) ----





    // ---- durable launcher owner/lease and takeover ----







    // ---- helpers ----

    private ToolJobAnchor buildReadyAnchor() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-1:1");
        anchor.setTaskId("task-123");
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTodoId("todo_3");
        anchor.setCompletedTodosJson(
                "[{\"todoId\":\"todo_1\",\"description\":\"fetch data\",\"summary\":\"done\",\"modelOutput\":\"ok\",\"sequence\":1,\"toolCalls\":1}," +
                 "{\"todoId\":\"todo_2\",\"description\":\"analyze\",\"summary\":\"done\",\"output\":\"result\",\"sequence\":2,\"toolCalls\":0}]");
        anchor.setDatasetSnapshotJson("{\"digest\":\"abc123\"}");
        anchor.setToolCallsUsed(2);
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalResultPreview("{\"result\":\"ok\"}");
        anchor.setResumeState("READY");
        anchor.setResumeToken("token-v1");
        anchor.setResumeLeaseVersion(5);
        return anchor;
    }

    private ToolJobAnchor expiredAcceptedAnchor(String ownerId, String token, long version) {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("ACCEPTED");
        anchor.setResumeClaimedAt(java.time.Instant.now().minusSeconds(60));
        anchor.setResumeLauncherOwnerId(ownerId);
        anchor.setResumeLauncherLeaseUntil(java.time.Instant.now().minusSeconds(1));
        anchor.setResumeToken(token);
        anchor.setResumeLeaseVersion(version);
        return anchor;
    }
}
