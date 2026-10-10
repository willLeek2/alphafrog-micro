package world.willfrog.agent.tools.router;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonStaticPrecheckWorkspaceTest {
    @Test
    void noNewDatasetShouldReachToolWhereWorkspaceIdentityCanBeChecked() {
        PythonStaticPrecheckService.Result result = new PythonStaticPrecheckService()
                .check("print(open('/sandbox/result.txt').read())", "", "", Map.of());

        assertTrue(result.isPassed());
        assertEquals(0, ((java.util.List<?>) result.getReport().get("dataset_ids")).size());
        assertEquals(0, ((java.util.List<?>) result.getReport().get("manifest_ids")).size());
    }
}
