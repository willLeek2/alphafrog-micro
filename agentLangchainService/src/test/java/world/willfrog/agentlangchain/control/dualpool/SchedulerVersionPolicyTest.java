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
 * <p>盯两件事：认不出的版本必须失败关闭；进程终止演练开关只能改默认值，不能改写显式配置的版本。</p>
 */
class SchedulerVersionPolicyTest {

    private static final String VERSION_KEY = "agent.langchain.dual-pool.new-run-scheduler-version";
    private static final String HALT_KEY = "agent.tool-job.fault-injection.allow-process-halt";

    private static SchedulerVersionPolicy policy(MockEnvironment environment) {
        // 与生产同一条路：版本由设置解析组件按「热配置 → 环境属性 → 代码默认」取，这里只给环境属性。
        return new SchedulerVersionPolicy(new DualPoolSchedulerSettings(null, environment));
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
    void haltSwitchDoesNotRewriteAnExplicitLegacy() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(HALT_KEY, "true")
                .withProperty(VERSION_KEY, "LEGACY");
        assertThat(policy(environment).versionForNewRun())
                .as("显式写了 LEGACY 就是要在旧入口上跑：演练开关只改默认值，不改操作人写下的版本")
                .isEqualTo("LEGACY");
    }

    /**
     * 演练开关改了默认值时，读数里要能看出「配置没写、生效的是演练后的版本」。
     *
     * <p>健康接口显示配置值、新 Run 却落库另一个版本，操作人会按错的版本验收。</p>
     */
    @Test
    void theSettingsReportBothTheConfiguredValueAndTheDrilledOne() {
        MockEnvironment environment = new MockEnvironment().withProperty(HALT_KEY, "true");
        DualPoolSchedulerSettings.Setting version =
                new DualPoolSchedulerSettings(null, environment).newRunSchedulerVersion();

        assertThat(version.textValue()).isEqualTo("DUAL_POOL_V1");
        assertThat(version.requested()).as("请求值：一层都没配，用代码默认").isEqualTo("LEGACY");
        assertThat(version.source()).isEqualTo(DualPoolSchedulerSettings.SOURCE_DEFAULT);
        assertThat(version.rejection()).contains("演练开关");
    }

    @Test
    void haltSwitchDoesNotRewriteAnExplicitDualPoolV1() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(HALT_KEY, "true")
                .withProperty(VERSION_KEY, "DUAL_POOL_V1");
        assertThat(policy(environment).versionForNewRun()).isEqualTo("DUAL_POOL_V1");
    }

    @Test
    void explicitDualPoolV2IsKept() {
        MockEnvironment environment = new MockEnvironment().withProperty(VERSION_KEY, "DUAL_POOL_V2");
        assertThat(policy(environment).versionForNewRun())
                .as("显式配置的新版本要原样用上：执行链已接通，用不用由配置决定")
                .isEqualTo("DUAL_POOL_V2");
    }

    @Test
    void explicitDualPoolV2IsNotRewrittenByTheHaltSwitch() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(VERSION_KEY, "DUAL_POOL_V2")
                .withProperty(HALT_KEY, "true");
        assertThat(policy(environment).versionForNewRun())
                .as("演练开关不得把显式配置的版本悄悄改成另一个版本")
                .isEqualTo("DUAL_POOL_V2");
    }

    @Test
    void everyDualPoolVersionIsRecognisedAsTheSameExecutionLayer() {
        SchedulerVersionPolicy policy = policy(new MockEnvironment());
        assertThat(policy.isDualPoolFamily("DUAL_POOL_V1")).isTrue();
        assertThat(policy.isDualPoolFamily("DUAL_POOL_V2"))
                .as("完整 DAG 也在双池执行层里：创建路径按家族受理，不是只认旧骨架")
                .isTrue();
        assertThat(policy.isDualPoolFamily("LEGACY")).isFalse();
        AgentRun v2 = new AgentRun();
        v2.setSchedulerVersion("DUAL_POOL_V2");
        assertThat(policy.isDualPoolFamily(v2)).isTrue();
        assertThat(policy.isDualPool(v2)).as("但它不是旧骨架，没有 Run 级单工具锚点").isFalse();
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
