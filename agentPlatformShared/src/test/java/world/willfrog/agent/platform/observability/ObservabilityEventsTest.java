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

    @Test
    void emitsContractFieldsWhenSwitchIsOn() throws Exception {
        attach();
        ObservabilityEvents.installSwitch(() -> true);
        MDC.put("trace_id", "trace-1");
        MDC.put("span_id", "span-1");

        ObservabilityEvents.workClaimed()
                .rootRunId("run-1")
                .nodeId("research")
                .segmentSequence(2)
                .operationId("work-9")
                .decision("claimed")
                .outcome("pending")
                .reasonCode("lease_granted")
                .summary("工作项被领取")
                .emit();

        JsonNode line = capturedJson().get(0);
        assertFalse(line.get("eventId").asText().isBlank());
        assertEquals("work_claimed", line.get("eventName").asText());
        assertEquals("info", line.get("severity").asText());
        assertTrue(line.get("occurredAt").asText().endsWith("+08:00"),
                "时刻要写成带东八区偏移的形式：" + line.get("occurredAt").asText());
        assertEquals("run-1", line.get("rootRunId").asText());
        assertEquals("research", line.get("nodeId").asText());
        assertEquals(2, line.get("segmentSequence").asInt());
        assertEquals("work-9", line.get("operationId").asText());
        assertEquals("claimed", line.get("decision").asText());
        assertEquals("pending", line.get("outcome").asText());
        assertEquals("lease_granted", line.get("reasonCode").asText());
        assertEquals("trace-1", line.get("traceId").asText());
        assertEquals("span-1", line.get("spanId").asText());
        assertEquals("工作项被领取", line.get("summary").asText());
        assertEquals("not_collected", line.get("detailStatus").asText());
        assertEquals("工作项被领取", appender.list.get(0).getFormattedMessage());
    }

    @Test
    void staysSilentWhenSwitchIsOff() {
        attach();
        ObservabilityEvents.installSwitch(null);
        ObservabilityEvents.workClaimed().rootRunId("run-1").summary("没开关").emit();
        ObservabilityEvents.installSwitch(() -> false);
        ObservabilityEvents.admissionDecided().rootRunId("run-1").summary("开关关").emit();

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void swallowsItsOwnFailures() {
        attach();
        ObservabilityEvents.installSwitch(() -> {
            throw new IllegalStateException("开关探针自己坏了");
        });

        assertDoesNotThrow(() -> ObservabilityEvents.workClaimed().rootRunId("run-1").summary("照常返回").emit());
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void omitsFieldsThatWereNotProvided() throws Exception {
        attach();
        ObservabilityEvents.installSwitch(() -> true);

        ObservabilityEvents.admissionDecided()
                .rootRunId("run-2")
                .decision("rejected")
                .emit();

        JsonNode line = capturedJson().get(0);
        assertFalse(line.has("nodeId"));
        assertFalse(line.has("segmentSequence"));
        assertFalse(line.has("traceId"));
        assertFalse(line.has("spanId"));
        assertEquals("admission_decided", line.get("summary").asText());
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
