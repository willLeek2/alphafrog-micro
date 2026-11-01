package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import world.willfrog.agent.platform.dataanalysis.SessionQueryAdmissionException;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class ToolJobAnchorServiceTest {

    @Mock
    private AgentRunMapper agentRunMapper;

    @Mock
    private AgentRunFinalizationService finalizationService;

    private ToolJobAnchorService anchorService;

    @BeforeEach
    void setUp() {
        anchorService = new ToolJobAnchorService(agentRunMapper);
        ReflectionTestUtils.setField(anchorService, "finalizationService", finalizationService);
    }























    // ---- CAS predicate binding tests (verify SQL WHERE clause arguments) ----








    // ===== 260818: CANCELED 终态收口专用 CAS（取消可落在恢复执行期） =====



    // ===== 260819: 终态 Run 残留取消锚点兜底收口 =====










}
