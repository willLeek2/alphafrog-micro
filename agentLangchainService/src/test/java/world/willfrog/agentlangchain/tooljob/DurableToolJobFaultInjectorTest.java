package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import world.willfrog.agent.platform.dataanalysis.ToolJobFaultInjector;
import world.willfrog.agent.platform.dataanalysis.ToolJobInjectedInterruption;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableToolJobFaultInjectorTest {

    @Test
    void consumedThreadScenarioClearsOnlyRunMemoryAndInterrupts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DeploymentIdentityProvider identities = mock(DeploymentIdentityProvider.class);
        DualPoolRunAdmissionRegistry admissions = mock(DualPoolRunAdmissionRegistry.class);
        when(identities.current()).thenReturn(new DeploymentIdentity(
                "beta-lane", "gen-" + "0".repeat(64)));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "scenario_id", "window-2-thread-1",
                "action", "THREAD_INTERRUPT")));
        DurableToolJobFaultInjector injector = new DurableToolJobFaultInjector(
                jdbc, identities, admissions, false);

        assertThatThrownBy(() -> injector.hit(
                "run-1", ToolJobFaultInjector.AFTER_SANDBOX_ACCEPTED))
                .isInstanceOf(ToolJobInjectedInterruption.class)
                .hasMessageContaining("window-2-thread-1")
                .hasMessageContaining(ToolJobFaultInjector.AFTER_SANDBOX_ACCEPTED);

        verify(admissions).forgetForFaultInjection("run-1");
    }

    @Test
    void noDurableScenarioDoesNothing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DeploymentIdentityProvider identities = mock(DeploymentIdentityProvider.class);
        DualPoolRunAdmissionRegistry admissions = mock(DualPoolRunAdmissionRegistry.class);
        when(identities.current()).thenReturn(new DeploymentIdentity(
                "beta-lane", "gen-" + "1".repeat(64)));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        DurableToolJobFaultInjector injector = new DurableToolJobFaultInjector(
                jdbc, identities, admissions, false);

        injector.hit("run-1", ToolJobFaultInjector.BEFORE_SANDBOX_SUBMIT);

        verify(admissions, never()).forgetForFaultInjection(anyString());
    }
}
