package world.willfrog.agent.tools.python;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import world.willfrog.alphafrogmicro.sandbox.idl.CollectedArtifactFile;
import world.willfrog.alphafrogmicro.sandbox.idl.SkippedArtifactFile;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 014-new-03 收割跳过日志的回归测试（c1 泳道实测教训）：
 * harvest 在「已收集文件数为 0」时早退，而跳过原因的日志循环原先排在早退之后，
 * 导致「全部文件都被跳过」（如单文件超限 exceeds_max_bytes）的场景在 agent 侧
 * 一条日志都不打，跳过原因只剩沙箱侧可见。此处钉住两条：全部被跳过时逐文件
 * 跳过原因必须在 agent 日志出现；有收集也有跳过时跳过日志同样保留。
 */
class RunOutputArtifactHarvesterTest {

    private final RunOutputArtifactHarvester harvester = new RunOutputArtifactHarvester();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(RunOutputArtifactHarvester.class);

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void allFilesSkippedStillLogsSkipReasons() {
        TaskResultResponse result = TaskResultResponse.newBuilder()
                .setTaskId("task-all-skipped")
                .addSkippedArtifacts(SkippedArtifactFile.newBuilder()
                        .setFilename("big_random.csv")
                        .setReason("exceeds_max_bytes"))
                .build();

        harvester.harvest("run-c1", "5", result);

        assertEquals(1, appender.list.size(), "全部被跳过时也应有一条跳过日志: " + appender.list);
        String message = appender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("big_random.csv"), "日志须含文件名: " + message);
        assertTrue(message.contains("exceeds_max_bytes"), "日志须含跳过原因: " + message);
        assertTrue(message.contains("run-c1"), "日志须含 runId: " + message);
    }

    @Test
    void collectedAndSkippedLogsSkipReasonsBeforeRegistryWiringCheck() {
        // 不注入登记表与落盘路径：跳过日志必须先于「接线缺失」告警打出（c2 部分
        // 收割场景的真实路径是接着走登记，这里只钉住跳过日志不受接线缺失影响）。
        TaskResultResponse result = TaskResultResponse.newBuilder()
                .setTaskId("task-partial")
                .addCollectedArtifacts(CollectedArtifactFile.newBuilder()
                        .setFilename("rand_0.csv"))
                .addSkippedArtifacts(SkippedArtifactFile.newBuilder()
                        .setFilename("rand_1.csv")
                        .setReason("total_budget_exceeded"))
                .build();

        harvester.harvest("run-c2", "5", result);

        String skipLine = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("rand_1.csv"))
                .findFirst().orElse("");
        assertTrue(!skipLine.isEmpty(), "有收集也有跳过时跳过日志必须在: " + appender.list);
        assertTrue(skipLine.contains("total_budget_exceeded"), "跳过日志须含原因: " + skipLine);
    }
}
