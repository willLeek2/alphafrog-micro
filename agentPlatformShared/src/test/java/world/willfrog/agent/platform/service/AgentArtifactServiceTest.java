package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentArtifactServiceTest {

    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentRunEventMapper agentRunEventMapper;

    private AgentArtifactService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new AgentArtifactService(eventService, agentRunEventMapper, new ObjectMapper());
        ReflectionTestUtils.setField(service, "artifactStoragePath", tempDir.resolve("artifacts").toString());
        ReflectionTestUtils.setField(service, "datasetPath", tempDir.resolve("datasets").toString());
        ReflectionTestUtils.setField(service, "normalRetentionDays", 7);
        ReflectionTestUtils.setField(service, "adminRetentionDays", 30);
        ReflectionTestUtils.setField(service, "downloadMaxBytes", 1024L * 1024L);
    }

    @Test
    void listArtifacts_shouldParseTodoEventsAndExportPythonScript() throws Exception {
        Path datasetDir = tempDir.resolve("datasets").resolve("ds1");
        Files.createDirectories(datasetDir);
        Files.writeString(datasetDir.resolve("ds1.csv"), "a,b\n1,2\n");
        Files.writeString(datasetDir.resolve("ds1.json"), "{\"columns\":[\"a\"],\"rows\":[[1]]}");
        Files.writeString(datasetDir.resolve("ds1.meta.json"), "{\"id\":\"ds1\"}");

        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("u1");
        run.setStartedAt(OffsetDateTime.now());

        AgentRunEvent e1 = new AgentRunEvent();
        e1.setRunId("run-1");
        e1.setSeq(1);
        e1.setEventType("TODO_LIST_CREATED");
        e1.setPayloadJson("{\"plan\":{\"items\":[{\"id\":\"todo_1\",\"toolName\":\"executePython\",\"params\":{\"code\":\"print(1)\",\"dataset_ids\":\"ds1\"}}]}}");
        e1.setCreatedAt(OffsetDateTime.now());

        AgentRunEvent e2 = new AgentRunEvent();
        e2.setRunId("run-1");
        e2.setSeq(2);
        e2.setEventType("TODO_STARTED");
        e2.setPayloadJson("{\"todo_id\":\"todo_1\",\"tool\":\"executePython\"}");
        e2.setCreatedAt(OffsetDateTime.now());

        AgentRunEvent e3 = new AgentRunEvent();
        e3.setRunId("run-1");
        e3.setSeq(3);
        e3.setEventType("TODO_FINISHED");
        e3.setPayloadJson("{\"todo_id\":\"todo_1\",\"success\":true,\"output_preview\":\"{\\\"ok\\\":true,\\\"data\\\":{\\\"dataset_id\\\":\\\"ds1\\\"}}\"}");
        e3.setCreatedAt(OffsetDateTime.now());

        when(eventService.listByRunId("run-1")).thenReturn(List.of(e1, e2, e3));

        var artifacts = service.listArtifacts(run, false);

        assertTrue(artifacts.stream().anyMatch(a -> "python_script".equals(a.getType())));
        assertTrue(artifacts.stream().anyMatch(a -> "dataset_csv".equals(a.getType()) && "ds1.csv".equals(a.getName())));
        assertTrue(artifacts.stream().anyMatch(a -> "dataset_json".equals(a.getType()) && "ds1.json".equals(a.getName())));
        assertTrue(artifacts.stream().anyMatch(a -> "dataset_meta".equals(a.getType()) && "ds1.meta.json".equals(a.getName())));
        var jsonArtifact = artifacts.stream()
                .filter(a -> "dataset_json".equals(a.getType()))
                .findFirst()
                .orElseThrow();
        assertTrue(jsonArtifact.getMetaJson().contains("\"dataset_id\":\"ds1\""));
        assertTrue(jsonArtifact.getMetaJson().contains("\"file_name\":\"ds1.json\""));
        assertTrue(artifacts.stream().allMatch(a -> a.getUrl().startsWith("/api/agent/runs/run-1/artifacts/")));
        assertEquals("{\"columns\":[\"a\"],\"rows\":[[1]]}",
                new String(service.loadArtifactForParts(run, false, jsonArtifact.getArtifactId()).content()));
    }
}
