package world.willfrog.agentlangchain.workspace;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.mapper.WaitGroupMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PythonWorkspaceExpirationServiceTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final WaitGroupMapper waitGroups = mock(WaitGroupMapper.class);
    private final PythonWorkspaceExpirationService service =
            new PythonWorkspaceExpirationService(runs, waitGroups);


}
