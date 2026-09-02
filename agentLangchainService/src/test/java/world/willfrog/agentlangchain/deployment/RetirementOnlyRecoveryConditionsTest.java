package world.willfrog.agentlangchain.deployment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import world.willfrog.agentlangchain.control.WorkflowStartupRecovery;
import world.willfrog.agentlangchain.tooljob.CancelReconciler;
import world.willfrog.agentlangchain.tooljob.ToolJobContinuationTracker;
import world.willfrog.agentlangchain.tooljob.ToolJobReconciler;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeLauncherHeartbeat;
import world.willfrog.agentlangchain.tooljob.ToolJobStartupRecovery;
import world.willfrog.agentlangchain.workspace.WorkspaceConfig;
import world.willfrog.agentlangchain.workspace.WorkspaceDumpScheduler;
import world.willfrog.agentlangchain.workspace.WorkspaceFinalizedEventListener;
import world.willfrog.agentlangchain.workspace.WorkspacePollingObserver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetirementOnlyRecoveryConditionsTest {

    @Test
    void everyWorkflowAndToolRecoveryEntryRequiresNormalRuntimeMode() {
        for (Class<?> recoveryType : List.of(
                WorkflowStartupRecovery.class,
                ToolJobStartupRecovery.class,
                ToolJobReconciler.class,
                ToolJobContinuationTracker.class,
                ToolJobResumeLauncherHeartbeat.class,
                CancelReconciler.class,
                WorkspaceConfig.class,
                WorkspaceDumpScheduler.class,
                WorkspaceFinalizedEventListener.class,
                WorkspacePollingObserver.class)) {
            ConditionalOnExpression condition = recoveryType
                    .getAnnotation(ConditionalOnExpression.class);
            assertThat(condition).as(recoveryType.getSimpleName() + " 必须有启动条件")
                    .isNotNull();
            assertThat(condition.value()).as(recoveryType.getSimpleName())
                    .contains("!${agent.deployment.retirement-only:false}");
        }
    }
}
