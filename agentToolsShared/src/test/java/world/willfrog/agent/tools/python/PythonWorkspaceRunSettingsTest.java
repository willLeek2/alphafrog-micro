package world.willfrog.agent.tools.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PythonWorkspaceRunSettingsTest {
    private static final String GENERATION = "gen-" + "a".repeat(64);



    private static AgentRun run(String ext) {
        AgentRun run = new AgentRun();
        run.setExt(ext);
        return run;
    }
}
