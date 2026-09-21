package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import world.willfrog.agent.platform.config.AgentLlmProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度参数的取值顺序、来源与读数。
 *
 * <p>量四件事：热配置优先于环境属性、环境属性优先于代码默认；不合法或互相矛盾的值被跳过并在读数里
 * 留下原因（而不是静默变成别的数）；每个值的来源与「改了要不要重启」都读得出来；读数覆盖全部双池参数
 * ——最后这一条按源码里出现的属性名逐条核对，漏一个就会失败。</p>
 */
class DualPoolSchedulerSettingsTest {

    private static final String PER_TURN = "agent.langchain.dual-pool.per-turn-new-node-limit";
    private static final String HIGH = "agent.langchain.dual-pool.global-unfinished-high-watermark";
    private static final String LOW = "agent.langchain.dual-pool.global-unfinished-low-watermark";
    private static final String VERSION = "agent.langchain.dual-pool.new-run-scheduler-version";

    /** 热配置优先：同一项在三层里都有，取热配置那一份。 */
    @Test
    void theHotConfigWinsOverTheEnvironmentProperty() {
        AgentLlmProperties.Scheduler scheduler = new AgentLlmProperties.Scheduler();
        scheduler.setPerTurnNewNodeLimit(3);
        DualPoolSchedulerSettings settings = TestSchedulerSettings.hot(scheduler,
                PER_TURN, "5", "agent.langchain.dual-pool.global-unfinished-high-watermark", "7");

        DualPoolSchedulerSettings.Setting setting = settings.perTurnNewNodeLimit();

        assertThat(setting.intValue()).isEqualTo(3);
        assertThat(setting.source()).isEqualTo(DualPoolSchedulerSettings.SOURCE_HOT_CONFIG);
        assertThat(setting.hotChangeable()).isTrue();
        assertThat(setting.rejection()).isNull();
    }

    /** 热配置没写这一项：用环境属性那层。 */
    @Test
    void anAbsentHotValueFallsBackToTheEnvironmentProperty() {
        DualPoolSchedulerSettings settings = TestSchedulerSettings.hot(new AgentLlmProperties.Scheduler(),
                PER_TURN, "5");

        DualPoolSchedulerSettings.Setting setting = settings.perTurnNewNodeLimit();

        assertThat(setting.intValue()).isEqualTo(5);
        assertThat(setting.source()).isEqualTo(DualPoolSchedulerSettings.SOURCE_PROPERTY);
    }

    /** 两层都没有：用代码默认。 */
    @Test
    void withoutAnyConfigurationTheCodeDefaultIsUsed() {
        DualPoolSchedulerSettings.Setting setting = TestSchedulerSettings.propertyOnly().perTurnNewNodeLimit();

        assertThat(setting.intValue()).isEqualTo(8);
        assertThat(setting.source()).isEqualTo(DualPoolSchedulerSettings.SOURCE_DEFAULT);
    }

    /** 热配置给的值不合法：跳过它、用下一层，并把原因留在读数里。 */
    @Test
    void anInvalidHotValueIsSkippedWithAReason() {
        AgentLlmProperties.Scheduler scheduler = new AgentLlmProperties.Scheduler();
        scheduler.setPerTurnNewNodeLimit(0);
        DualPoolSchedulerSettings settings = TestSchedulerSettings.hot(scheduler, PER_TURN, "5");

        DualPoolSchedulerSettings.Setting setting = settings.perTurnNewNodeLimit();

        assertThat(setting.intValue()).as("不能用那个 0，也不能把它悄悄改成 1").isEqualTo(5);
        assertThat(setting.source()).isEqualTo(DualPoolSchedulerSettings.SOURCE_PROPERTY);
        assertThat(setting.rejection()).contains("不合法");
    }

    /** 低水位高于高水位：这组组合不成立，用高水位并把原因写清楚。 */
    @Test
    void aLowWatermarkAboveTheHighOneIsReportedAndNotSilentlyKept() {
        AgentLlmProperties.Scheduler scheduler = new AgentLlmProperties.Scheduler();
        scheduler.setGlobalUnfinishedHighWatermark(100);
        scheduler.setGlobalUnfinishedLowWatermark(150);
        DualPoolSchedulerSettings settings = TestSchedulerSettings.hot(scheduler);

        DualPoolSchedulerSettings.Setting low = settings.globalUnfinishedLowWatermark();
        DualPoolSchedulerSettings.Setting high = settings.globalUnfinishedHighWatermark();

        assertThat(high.intValue()).isEqualTo(100);
        assertThat(low.intValue()).isEqualTo(100);
        assertThat(low.rejection()).contains("低水位高于高水位");
    }

    /** 热配置里的退避上限小于初值：跳过它、用下一层里那个站得住的值，并把原因写清楚。 */
    @Test
    void aHotBackoffCeilingBelowItsFloorIsSkippedWithAReason() {
        AgentLlmProperties.Scheduler scheduler = new AgentLlmProperties.Scheduler();
        scheduler.setRecoveryBackoffBaseMs(2000L);
        scheduler.setRecoveryBackoffMaxMs(500L);
        DualPoolSchedulerSettings settings = TestSchedulerSettings.hot(scheduler);

        DualPoolSchedulerSettings.Setting ceiling = settings.recoveryBackoffMaxMs();

        assertThat(ceiling.longValue()).as("下一层是代码默认的 5000，它比初值 2000 大，站得住").isEqualTo(5000L);
        assertThat(ceiling.source()).isEqualTo(DualPoolSchedulerSettings.SOURCE_DEFAULT);
        assertThat(ceiling.rejection()).contains("不合法").contains("退避初值");
    }

    /** 初值本身就比任何一层能给出的上限都大：上限取初值，并把原因写清楚。 */
    @Test
    void aBackoffCeilingBelowItsFloorIsReported() {
        AgentLlmProperties.Scheduler scheduler = new AgentLlmProperties.Scheduler();
        scheduler.setRecoveryBackoffBaseMs(9000L);
        DualPoolSchedulerSettings settings = TestSchedulerSettings.hot(scheduler);

        DualPoolSchedulerSettings.Setting ceiling = settings.recoveryBackoffMaxMs();

        assertThat(ceiling.longValue()).as("上限不能小于初值").isEqualTo(9000L);
        assertThat(ceiling.rejection()).contains("退避上限小于初值");
    }

    /** 环境属性写了个不是数字的值：用默认值，并把原因写清楚。 */
    @Test
    void aNonNumericPropertyIsReportedAndFallsBackToTheDefault() {
        DualPoolSchedulerSettings settings = TestSchedulerSettings.propertyOnly(PER_TURN, "八");

        DualPoolSchedulerSettings.Setting setting = settings.perTurnNewNodeLimit();

        assertThat(setting.intValue()).isEqualTo(8);
        assertThat(setting.source()).isEqualTo(DualPoolSchedulerSettings.SOURCE_DEFAULT);
        assertThat(setting.rejection()).contains("环境属性的值不合法");
    }

    /** 认不出的调度器版本：值照读出来（不换成别的版本），读数里写明新 Run 会被拒绝。 */
    @Test
    void anUnknownSchedulerVersionIsReadableAndMarkedAsRejected() {
        DualPoolSchedulerSettings settings = TestSchedulerSettings.propertyOnly(VERSION, "DUAL_POOL_V9");

        DualPoolSchedulerSettings.Setting setting = settings.newRunSchedulerVersion();

        assertThat(setting.textValue()).isEqualTo("DUAL_POOL_V9");
        assertThat(setting.rejection()).contains("认不出的调度器版本");
    }

    /** 冻结类参数：读得出来，但标明改了要重启。 */
    @Test
    void startupFrozenValuesAreReadableAndMarkedAsSuch() {
        DualPoolSchedulerSettings settings = TestSchedulerSettings.propertyOnly(
                "agent.langchain.dual-pool.node-worker.permit-limit", "6");

        DualPoolSchedulerSettings.Setting permits = settings.snapshot()
                .get("agent.langchain.dual-pool.node-worker.permit-limit");
        DualPoolSchedulerSettings.Setting poolSize = settings.snapshot()
                .get("agent.langchain.dual-pool.node-worker.core-pool-size");

        assertThat(permits.intValue()).isEqualTo(6);
        assertThat(permits.hotChangeable()).as("许可上限只能在启动时生效").isFalse();
        assertThat(poolSize.intValue()).as("没配就用 yml 里的默认值").isEqualTo(4);
        assertThat(poolSize.hotChangeable()).isFalse();
    }

    /**
     * 读数必须覆盖全部双池参数。
     *
     * <p>做法是拿源码里真正读过的属性名来核对：任何一处 {@code @Value} 读的双池参数，读数里必须有；
     * 反过来说，读数里少了一样东西就会失败。这样以后新加参数的人不会漏掉读数——漏了用例会挡住。</p>
     */
    @Test
    void theSnapshotCoversEveryDualPoolPropertyReadAnywhereInTheSources() throws IOException {
        Set<String> readEverywhere = new LinkedHashSet<>();
        for (Path module : List.of(Path.of(System.getProperty("user.dir")),
                Path.of(System.getProperty("user.dir")).resolve("../agentPlatformShared"),
                Path.of(System.getProperty("user.dir")).resolve("../agentToolsShared"))) {
            Path sourceRoot = module.resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    readEverywhere.addAll(dualPoolPropertyKeys(Files.readString(file)));
                }
            }
        }
        Map<String, DualPoolSchedulerSettings.Setting> snapshot = TestSchedulerSettings.propertyOnly().snapshot();

        List<String> missing = new ArrayList<>();
        for (String key : readEverywhere) {
            if (!snapshot.containsKey(key)) {
                missing.add(key);
            }
        }

        assertThat(readEverywhere).as("源码里至少要读得到这些参数，否则这个用例自己失去了意义").isNotEmpty();
        assertThat(missing).as("这些参数在读数里读不到，得补进设置解析组件").isEmpty();
        for (Map.Entry<String, DualPoolSchedulerSettings.Setting> entry : snapshot.entrySet()) {
            assertThat(entry.getValue().source()).as(entry.getKey()).isNotBlank();
            assertThat(entry.getValue().value()).as(entry.getKey()).isNotNull();
        }
    }

    /** 从一段源码里挑出读双池参数的属性名（`@Value` 与定时任务的定时间隔都算）。 */
    private static Set<String> dualPoolPropertyKeys(String source) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                "agent\\.langchain\\.(?:dual-pool|wait-member)\\.[a-z0-9.\\-]+").matcher(source);
        while (matcher.find()) {
            String key = matcher.group();
            // 只认完整的属性名：后面跟着冒号（@Value 的默认值）或者引号收尾。
            // 拼接出来的前缀（常量 + 后缀）会匹配到半截名字，以点结尾的那种不算一个参数名。
            if (key.endsWith(".")) {
                continue;
            }
            int end = matcher.end();
            if (end < source.length()) {
                char next = source.charAt(end);
                if (next == ':' || next == '"' || next == '}') {
                    keys.add(key);
                }
            }
        }
        return keys;
    }
}
