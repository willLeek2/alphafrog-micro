package world.willfrog.agentlangchain.deployment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.DeploymentGenerationRecord;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

class DeploymentGenerationReaperTest {

    private static final String FAILURE = "deployment_generation_shutdown_deadline_exceeded";







    private static Fixture fixture(int batchSize) {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = mock(DeploymentIdentityProvider.class);
        DeploymentGenerationLivenessProbe probe = mock(DeploymentGenerationLivenessProbe.class);
        DeploymentIdentity local = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        DeploymentIdentity old = new DeploymentIdentity("beta-a", "gen-" + "b".repeat(64));
        AtomicLong now = new AtomicLong();
        when(provider.current()).thenReturn(local);
        DeploymentGenerationReaper reaper = new DeploymentGenerationReaper(
                runMapper, provider, probe, Duration.ofSeconds(60), batchSize, now::get);
        return new Fixture(runMapper, probe, local, old, now, reaper);
    }

    private static DeploymentGenerationRecord record(DeploymentIdentity identity) {
        DeploymentGenerationRecord record = new DeploymentGenerationRecord();
        record.setDeploymentId(identity.deploymentId());
        record.setDeploymentGenerationId(identity.generationId());
        return record;
    }

    private record Fixture(AgentRunMapper runMapper,
                           DeploymentGenerationLivenessProbe probe,
                           DeploymentIdentity local,
                           DeploymentIdentity old,
                           AtomicLong now,
                           DeploymentGenerationReaper reaper) {
    }
}
