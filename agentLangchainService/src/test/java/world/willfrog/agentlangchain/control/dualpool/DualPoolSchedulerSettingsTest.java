package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
