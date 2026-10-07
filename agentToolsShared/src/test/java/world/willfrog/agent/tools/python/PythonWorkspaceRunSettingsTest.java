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

    @Test
    void workspaceOnlyCallUsesStableEmptyInputSnapshotIdentity() {
        assertEquals("sha256:047a4e741f68a028dd46a1fdbd1dec12d87c8a2123abaa9f36abd5c4506ca2e8",
                AgentRunDatasetSnapshot.empty().immutableDigest());
    }

    @Test
    void readsEachOwnedRunSnapshotAndKeepsOldRunsDisabled() {
        PythonSandboxTools tools = new PythonSandboxTools(new ObjectMapper());
        AgentRunMapper runs = mock(AgentRunMapper.class);
        DeploymentIdentityProvider deployment = () -> new DeploymentIdentity("stable", GENERATION);
        ReflectionTestUtils.setField(tools, "agentRunMapper", runs);
        ReflectionTestUtils.setField(tools, "deploymentIdentityProvider", deployment);
        when(runs.findByIdForDeployment("new-run", "stable", GENERATION))
                .thenReturn(run("{\"python_workspace_enabled\":true}"));
        when(runs.findByIdForDeployment("old-run", "stable", GENERATION))
                .thenReturn(run("{}"));
        when(runs.findByIdForDeployment("disabled-run", "stable", GENERATION))
                .thenReturn(run("{\"python_workspace_enabled\":false}"));

        assertTrue(tools.pythonWorkspaceEnabledForRun("new-run"));
        assertFalse(tools.pythonWorkspaceEnabledForRun("old-run"));
        assertFalse(tools.pythonWorkspaceEnabledForRun("disabled-run"));
        assertThrows(IllegalStateException.class,
                () -> tools.pythonWorkspaceEnabledForRun("other-deployment-run"));
    }

    private static AgentRun run(String ext) {
        AgentRun run = new AgentRun();
        run.setExt(ext);
        return run;
    }
}
