package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AgentRunFamilyDeletionServiceTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final ChildRunIntentStore intents = mock(ChildRunIntentStore.class);
    private final AgentRunFamilyDeletionService service = new AgentRunFamilyDeletionService(runs, intents);









    private static AgentRun run(String id, String userId, AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId(userId);
        run.setStatus(status);
        return run;
    }
}
