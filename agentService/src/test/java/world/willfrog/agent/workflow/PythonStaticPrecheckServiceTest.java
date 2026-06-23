package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.tools.router.PythonStaticPrecheckService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonStaticPrecheckServiceTest {

    private final PythonStaticPrecheckService service = new PythonStaticPrecheckService();

    @Test
    void check_shouldFailWithMissingIdsWhenBothDatasetAndManifestEmpty() {
        // 260623-harness-optimization-02: dataset_ids / manifest_ids 都为空 → MISSING_IDS
        PythonStaticPrecheckService.Result result = service.check("print(1)", "", "", Map.of());

        assertFalse(result.isPassed());
        assertEquals("MISSING_IDS", result.getErrorCode());
    }

    @Test
    void check_shouldFailWithMissingIdsWhenDatasetEmptyManifestEmpty() {
        // 显式 null 也走 MISSING_IDS 分支
        PythonStaticPrecheckService.Result result = service.check("print(1)", null, null, Map.of());

        assertFalse(result.isPassed());
        assertEquals("MISSING_IDS", result.getErrorCode());
    }

    @Test
    void check_shouldPassWhenCodeAndDatasetIdsPresent() {
        PythonStaticPrecheckService.Result result = service.check(
                "print(1)",
                "ds-1",
                "",
                Map.of()
        );

        assertTrue(result.isPassed());
    }

    @Test
    void check_shouldPassWhenOnlyManifestIdsPresent() {
        // 260623-harness-optimization-02: manifest_ids 非空也能通过（独立空间）
        PythonStaticPrecheckService.Result result = service.check(
                "print(1)",
                "",
                "mf-1",
                Map.of()
        );

        assertTrue(result.isPassed());
    }

    @Test
    void check_shouldPassWhenBothDatasetAndManifestPresent() {
        PythonStaticPrecheckService.Result result = service.check(
                "print(1)",
                "ds-1",
                "mf-1",
                Map.of()
        );

        assertTrue(result.isPassed());
    }

    @Test
    void check_shouldFailWithStaticPrecheckWhenForbiddenPathUsed() {
        PythonStaticPrecheckService.Result result = service.check(
                "open('/datasets/foo.csv')",
                "ds-1",
                "",
                Map.of()
        );

        assertFalse(result.isPassed());
        assertEquals("STATIC_PRECHECK_FAILED", result.getErrorCode());
    }

    @Test
    void check_shouldFailWithStaticPrecheckWhenManifestIdFormatIllegal() {
        // 260623-harness-optimization-02: 非法 manifest_id 格式也走 STATIC_PRECHECK_FAILED
        PythonStaticPrecheckService.Result result = service.check(
                "print(1)",
                "ds-1",
                "mf/1",
                Map.of()
        );

        assertFalse(result.isPassed());
        assertEquals("STATIC_PRECHECK_FAILED", result.getErrorCode());
    }
}
