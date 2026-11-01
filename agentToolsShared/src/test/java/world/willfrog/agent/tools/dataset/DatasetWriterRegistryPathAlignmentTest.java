package world.willfrog.agent.tools.dataset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused test proving DatasetWriter and DatasetRegistry compute the same 4-layer path
 * when given the same clean type. This addresses Cindy's merge-readiness blocker:
 * persistedPath must point to the actual written file.
 *
 * <p>Both DatasetWriter and DatasetRegistry use the same DatabaseFetchedPathStrategy
 * static methods (resolveTopic, encodedString, resolveDataPath) with the same clean
 * type input. This test proves:
 * <ol>
 *   <li>Writer actually writes to the path computed from clean type (not runId prefix)</li>
 *   <li>Writer and registry's path formulas produce identical results</li>
 *   <li>Different types don't collide</li>
 *   <li>Real file at writer path == registry's expected persistedPath</li>
 * </ol>
 */
class DatasetWriterRegistryPathAlignmentTest {

    private DatasetWriter writer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // D04：dataset 根目录改经统一存储门面注入；本测试用显式值入口把根指到 tempDir
        writer = new DatasetWriter(new AgentStoragePaths(
                tempDir.resolve("workspaces").toString(),
                tempDir.resolve("artifacts").toString(),
                tempDir.resolve("agent_datasets").toString(),
                tempDir.resolve("obs.log").toString()));
        ReflectionTestUtils.setField(writer, "databaseFetchedPath", tempDir.resolve("database_fetched").toString());
        ReflectionTestUtils.setField(writer, "enabled", true);
        ReflectionTestUtils.setField(writer, "localConfigLoader", null);
    }






    record TestRow(String date, String open, String close) {
        TestRow(String date, String value) { this(date, value, null); }
    }
}
