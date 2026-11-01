package world.willfrog.agentlangchain.control.dualpool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentLlmHotConfigNotSyncedException;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.workitem.UnknownSchedulerVersionException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
















}
