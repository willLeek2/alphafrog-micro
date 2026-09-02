package world.willfrog.agentlangchain.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import world.willfrog.agent.platform.mapper.AgentRunMapper;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 260814 scheduler-03：Workspace export 总开关的上下文级门禁契约测试。
 *
 * <p>验证 agent.workspace.export-enabled 在 Bean 注册层的作用：false（含缺失
 * 时的默认值）时四个相关 Bean 均不存在——WorkspaceConfig 定义的
 * workspaceDumpExecutor 线程池、WorkspaceDumpScheduler（含启动重放监听器）、
 * WorkspacePollingObserver（含 @Scheduled 轮询）、WorkspaceFinalizedEventListener
 * （终态事件副作用）；true 时四个 Bean 全部注册。门禁在类级条件上评估，关闭时
 * 相关构造依赖都不需要解析。</p>
 */
class WorkspaceExportSwitchTest {

    @TempDir
    Path tempDir;

    private ApplicationContextRunner runner() {
        WorkspaceDumpService dumpService = mock(WorkspaceDumpService.class);
        WorkspacePathResolver pathResolver = mock(WorkspacePathResolver.class);
        when(pathResolver.getWorkspaceRoot()).thenReturn(tempDir);
        return new ApplicationContextRunner()
                .withBean(WorkspaceDumpService.class, () -> dumpService)
                .withBean(WorkspacePathResolver.class, () -> pathResolver)
                .withBean(AgentRunMapper.class, () -> mock(AgentRunMapper.class))
                .withUserConfiguration(
                        WorkspaceConfig.class,
                        WorkspaceDumpScheduler.class,
                        WorkspacePollingObserver.class,
                        WorkspaceFinalizedEventListener.class);
    }

    @Test
    void exportEnabledFalse_shouldRegisterNoneOfTheFourBeans() {
        runner().withPropertyValues("agent.workspace.export-enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean("workspaceDumpExecutor");
            assertThat(ctx).doesNotHaveBean(WorkspaceDumpScheduler.class);
            assertThat(ctx).doesNotHaveBean(WorkspacePollingObserver.class);
            assertThat(ctx).doesNotHaveBean(WorkspaceFinalizedEventListener.class);
        });
    }

    @Test
    void exportEnabledMissing_shouldDefaultToOff() {
        // matchIfMissing=false：不配置任何属性时同样全部不注册（默认关闭合同）
        runner().run(ctx -> {
            assertThat(ctx).doesNotHaveBean("workspaceDumpExecutor");
            assertThat(ctx).doesNotHaveBean(WorkspaceDumpScheduler.class);
            assertThat(ctx).doesNotHaveBean(WorkspacePollingObserver.class);
            assertThat(ctx).doesNotHaveBean(WorkspaceFinalizedEventListener.class);
        });
    }

    @Test
    void exportEnabledTrue_shouldRegisterAllFourBeans() {
        runner().withPropertyValues("agent.workspace.export-enabled=true").run(ctx -> {
            assertThat(ctx).hasBean("workspaceDumpExecutor");
            assertThat(ctx).hasSingleBean(WorkspaceDumpScheduler.class);
            assertThat(ctx).hasSingleBean(WorkspacePollingObserver.class);
            assertThat(ctx).hasSingleBean(WorkspaceFinalizedEventListener.class);
        });
    }

    @Test
    void retirementOnlyModeDisablesWorkspaceRecoveryEvenWhenExportWasEnabled() {
        runner().withPropertyValues(
                "agent.workspace.export-enabled=true",
                "agent.deployment.retirement-only=true").run(ctx -> {
            assertThat(ctx).doesNotHaveBean("workspaceDumpExecutor");
            assertThat(ctx).doesNotHaveBean(WorkspaceDumpScheduler.class);
            assertThat(ctx).doesNotHaveBean(WorkspacePollingObserver.class);
            assertThat(ctx).doesNotHaveBean(WorkspaceFinalizedEventListener.class);
        });
    }
}
