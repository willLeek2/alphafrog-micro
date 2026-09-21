package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.workitem.UnknownSchedulerVersionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 新 Run 调度器版本的解析规则。
 *
 * <p>盯两件事：没接通执行链的版本必须失败关闭；进程终止演练开关只能改默认值，不能改写显式配置的版本。</p>
 */
class SchedulerVersionPolicyTest {

    private static final String VERSION_KEY = "agent.langchain.dual-pool.new-run-scheduler-version";
    private static final String HALT_KEY = "agent.tool-job.fault-injection.allow-process-halt";

    private static SchedulerVersionPolicy policy(MockEnvironment environment) {
        // 与生产同一条路：版本由设置解析组件按「热配置 → 环境属性 → 代码默认」取，这里只给环境属性。
        return new SchedulerVersionPolicy(environment,
                new DualPoolSchedulerSettings(null, environment));
    }

    @Test
    void unconfiguredFallsBackToLegacy() {
        assertThat(policy(new MockEnvironment()).versionForNewRun()).isEqualTo("LEGACY");
    }

    @Test
    void explicitDualPoolV1IsKept() {
        MockEnvironment environment = new MockEnvironment().withProperty(VERSION_KEY, "DUAL_POOL_V1");
        assertThat(policy(environment).versionForNewRun()).isEqualTo("DUAL_POOL_V1");
    }

    @Test
    void haltSwitchOnlyMovesTheDefaultToDualPoolV1() {
        MockEnvironment environment = new MockEnvironment().withProperty(HALT_KEY, "true");
        assertThat(policy(environment).versionForNewRun())
                .as("泳道显式授权进程终止演练时，没配置版本的新 Run 进双池")
                .isEqualTo("DUAL_POOL_V1");
    }

    @Test
    void haltSwitchDoesNotRewriteAnExplicitDualPoolV1() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(HALT_KEY, "true")
                .withProperty(VERSION_KEY, "DUAL_POOL_V1");
        assertThat(policy(environment).versionForNewRun()).isEqualTo("DUAL_POOL_V1");
    }

    @Test
    void dualPoolV2FailsClosedWithTheHaltSwitchOff() {
        MockEnvironment environment = new MockEnvironment().withProperty(VERSION_KEY, "DUAL_POOL_V2");
        assertThatThrownBy(() -> policy(environment).versionForNewRun())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DUAL_POOL_V2 的执行链尚未接通");
    }

    @Test
    void dualPoolV2FailsClosedEvenWithTheHaltSwitchOn() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(VERSION_KEY, "DUAL_POOL_V2")
                .withProperty(HALT_KEY, "true");
        assertThatThrownBy(() -> policy(environment).versionForNewRun())
                .as("演练开关不得把显式配置的版本悄悄改成另一个版本")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DUAL_POOL_V2 的执行链尚未接通");
    }

    @Test
    void unknownConfiguredVersionFailsClosed() {
        MockEnvironment environment = new MockEnvironment().withProperty(VERSION_KEY, "DUAL_POOL_V9");
        assertThatThrownBy(() -> policy(environment).versionForNewRun())
                .isInstanceOf(UnknownSchedulerVersionException.class)
                .hasMessageContaining("DUAL_POOL_V9");
    }

    @Test
    void unknownVersionAlreadyRecordedOnARunFailsClosedToo() {
        AgentRun run = new AgentRun();
        run.setSchedulerVersion("DUAL_POOL_V9");
        assertThatThrownBy(() -> policy(new MockEnvironment()).versionOf(run))
                .as("已经落库的版本不认识时不能回落到任何一个已知版本")
                .isInstanceOf(UnknownSchedulerVersionException.class);
    }
}
