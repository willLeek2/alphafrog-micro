package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetSandboxMetadataWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;





}
