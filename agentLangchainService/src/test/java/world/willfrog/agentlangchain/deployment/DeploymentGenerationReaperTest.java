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

    @Test
    void requiresAnAbsenceConfirmationAndASecondCheckBeforeWritingABoundedBatch() {
        Fixture fixture = fixture(32);
        when(fixture.runMapper.listNonTerminalDeploymentGenerations(
                fixture.local.deploymentId(), fixture.local.generationId()))
                .thenReturn(List.of(record(fixture.old)));
        when(fixture.probe.hasLiveInstance(fixture.old)).thenReturn(false);

        fixture.reaper.sweep();
        verify(fixture.runMapper, never()).failOrphanedNonTerminalRunsForDeploymentGeneration(
                fixture.old.deploymentId(), fixture.old.generationId(), FAILURE, 32);

        fixture.now.set(Duration.ofSeconds(60).toNanos());
        fixture.reaper.sweep();

        verify(fixture.probe, times(3)).hasLiveInstance(fixture.old);
        verify(fixture.runMapper).failOrphanedNonTerminalRunsForDeploymentGeneration(
                fixture.old.deploymentId(), fixture.old.generationId(), FAILURE, 32);
    }

    @Test
    void aLiveInstanceStartsANewAbsenceWindow() {
        Fixture fixture = fixture(32);
        when(fixture.runMapper.listNonTerminalDeploymentGenerations(
                fixture.local.deploymentId(), fixture.local.generationId()))
                .thenReturn(List.of(record(fixture.old)));
        when(fixture.probe.hasLiveInstance(fixture.old)).thenReturn(false, true, false);

        fixture.reaper.sweep();
        fixture.now.set(Duration.ofSeconds(60).toNanos());
        fixture.reaper.sweep();
        fixture.now.set(Duration.ofSeconds(120).toNanos());
        fixture.reaper.sweep();

        verify(fixture.runMapper, never()).failOrphanedNonTerminalRunsForDeploymentGeneration(
                fixture.old.deploymentId(), fixture.old.generationId(), FAILURE, 32);
    }

    @Test
    void anUncertainRegistryReadSkipsThatSweep() {
        Fixture fixture = fixture(32);
        when(fixture.runMapper.listNonTerminalDeploymentGenerations(
                fixture.local.deploymentId(), fixture.local.generationId()))
                .thenReturn(List.of(record(fixture.old)));
        when(fixture.probe.hasLiveInstance(fixture.old))
                .thenReturn(false)
                .thenThrow(new IllegalStateException("registry unavailable"))
                .thenReturn(false, false);

        fixture.reaper.sweep();
        fixture.now.set(Duration.ofSeconds(60).toNanos());
        fixture.reaper.sweep();
        verify(fixture.runMapper, never()).failOrphanedNonTerminalRunsForDeploymentGeneration(
                fixture.old.deploymentId(), fixture.old.generationId(), FAILURE, 32);

        fixture.now.set(Duration.ofSeconds(120).toNanos());
        fixture.reaper.sweep();
        verify(fixture.runMapper).failOrphanedNonTerminalRunsForDeploymentGeneration(
                fixture.old.deploymentId(), fixture.old.generationId(), FAILURE, 32);
    }

    @Test
    void stableDeploymentIsRejectedEvenIfTheCandidateQueryReturnsIt() {
        Fixture fixture = fixture(32);
        DeploymentIdentity stable = new DeploymentIdentity("stable", "gen-" + "c".repeat(64));
        when(fixture.runMapper.listNonTerminalDeploymentGenerations(
                fixture.local.deploymentId(), fixture.local.generationId()))
                .thenReturn(List.of(record(stable)));

        fixture.reaper.sweep();

        verify(fixture.probe, never()).hasLiveInstance(stable);
        verify(fixture.runMapper, never()).failOrphanedNonTerminalRunsForDeploymentGeneration(
                stable.deploymentId(), stable.generationId(), FAILURE, 32);
    }

    @Test
    void aCandidateThatDisappearsFromTheDatabaseGetsANewAbsenceWindowIfItReturns() {
        Fixture fixture = fixture(32);
        when(fixture.runMapper.listNonTerminalDeploymentGenerations(
                fixture.local.deploymentId(), fixture.local.generationId()))
                .thenReturn(List.of(record(fixture.old)), List.of(), List.of(record(fixture.old)));
        when(fixture.probe.hasLiveInstance(fixture.old)).thenReturn(false);

        fixture.reaper.sweep();
        fixture.now.set(Duration.ofSeconds(60).toNanos());
        fixture.reaper.sweep();
        fixture.now.set(Duration.ofSeconds(120).toNanos());
        fixture.reaper.sweep();

        verify(fixture.runMapper, never()).failOrphanedNonTerminalRunsForDeploymentGeneration(
                fixture.old.deploymentId(), fixture.old.generationId(), FAILURE, 32);
    }

    @Test
    void oneSweepNeverWritesMoreThanTheConfiguredBatch() {
        Fixture fixture = fixture(32);
        DeploymentIdentity another = new DeploymentIdentity("beta-b", "gen-" + "c".repeat(64));
        when(fixture.runMapper.listNonTerminalDeploymentGenerations(
                fixture.local.deploymentId(), fixture.local.generationId()))
                .thenReturn(List.of(record(fixture.old), record(another)));
        when(fixture.probe.hasLiveInstance(fixture.old)).thenReturn(false);
        when(fixture.probe.hasLiveInstance(another)).thenReturn(false);
        when(fixture.runMapper.failOrphanedNonTerminalRunsForDeploymentGeneration(
                fixture.old.deploymentId(), fixture.old.generationId(), FAILURE, 32)).thenReturn(32);

        fixture.reaper.sweep();
        fixture.now.set(Duration.ofSeconds(60).toNanos());
        fixture.reaper.sweep();

        verify(fixture.runMapper).failOrphanedNonTerminalRunsForDeploymentGeneration(
                fixture.old.deploymentId(), fixture.old.generationId(), FAILURE, 32);
        verify(fixture.runMapper, never()).failOrphanedNonTerminalRunsForDeploymentGeneration(
                another.deploymentId(), another.generationId(), FAILURE, 32);
    }

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
