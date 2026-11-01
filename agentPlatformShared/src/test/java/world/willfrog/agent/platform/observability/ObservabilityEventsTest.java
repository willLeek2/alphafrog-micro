package world.willfrog.agent.platform.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上报入口自身的四条要求：开关关时不出声、开时字段按合同、可空字段不硬塞、自身出错不外抛。
 * 日志行按生产同一套编码器（logstash）编码后断言，验证字段确实落在 JSON 顶层。
 */
class ObservabilityEventsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Logger eventLogger = (Logger) LoggerFactory.getLogger("observability.events");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private void attach() {
        appender.start();
        eventLogger.addAppender(appender);
    }

    @AfterEach
    void cleanUp() {
        eventLogger.detachAppender(appender);
        appender.stop();
        appender.list.clear();
        MDC.clear();
        ObservabilityEvents.installSwitch(null);
    }





    private List<JsonNode> capturedJson() throws Exception {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(eventLogger.getLoggerContext());
        encoder.start();
        try {
            List<JsonNode> lines = new ArrayList<>();
            for (ILoggingEvent event : appender.list) {
                lines.add(OBJECT_MAPPER.readTree(new String(encoder.encode(event), StandardCharsets.UTF_8)));
            }
            return lines;
        } finally {
            encoder.stop();
        }
    }
}
