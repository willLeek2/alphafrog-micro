package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunBudgetService;
import world.willfrog.agent.platform.wait.WaitGroupMemberExecutionContext;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.tools.dataanalysis.SqlQueryTools;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SqlWaitMemberSessionAdmissionTest {
    @TempDir Path temp;

}
