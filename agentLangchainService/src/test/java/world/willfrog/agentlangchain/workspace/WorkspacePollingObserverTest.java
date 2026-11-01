package world.willfrog.agentlangchain.workspace;

import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspacePollingObserverTest {









    private static AgentRun run(String id, AgentRunStatus status) {
        return run(id, status, OffsetDateTime.now());
    }

    private static AgentRun run(String id, AgentRunStatus status, OffsetDateTime updatedAt) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setStatus(status);
        run.setUpdatedAt(updatedAt);
        return run;
    }
}
