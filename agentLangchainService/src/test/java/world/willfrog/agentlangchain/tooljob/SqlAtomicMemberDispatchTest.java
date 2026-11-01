package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.service.AgentRunBudgetService;
import world.willfrog.agent.platform.wait.*;
import world.willfrog.agent.platform.workitem.*;
import world.willfrog.agent.tools.dataanalysis.SqlQueryTools;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.workflow.*;
import world.willfrog.alphafrogmicro.sandbox.idl.*;
import world.willfrog.agentlangchain.control.dualpool.DualPoolToolJobExecutionContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlAtomicMemberDispatchTest {
    @TempDir Path temp;
    enum Mode { FRESH, BEFORE, AFTER, INTERRUPTED_COMMITTED, INTERRUPTED_NOT_WRITTEN, BUSY_READBACK, CANCELED_READBACK, CANCELED_NO_WRITE, RESTORE_FAILED, CAS_ZERO_NO_WRITE, CAS_ZERO_COMMITTED, COMMIT_INTERRUPTED }
    @AfterEach void cleanThread() { Thread.interrupted(); MemberPreparingInterruption.consume(); AgentContext.clear(); }

}
