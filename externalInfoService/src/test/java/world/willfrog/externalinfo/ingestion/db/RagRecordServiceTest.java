package world.willfrog.externalinfo.ingestion.db;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagRecordServiceTest {

    @Test
    void titlePatternsUseOrContains() {
        CapturedQuery captured = runFindUnprocessed(req -> {
            req.setDocType("announcement");
            req.setTitlePatterns(List.of("年度报告", "年报"));
        });

        assertTrue(captured.sql.contains("(d.title LIKE ? OR d.title LIKE ?)"));
        assertArrayEquals(new Object[]{"%年度报告%", "%年报%", 50, 0}, captured.params);
    }

    @Test
    void titleMatchIncludeExcludeUsesContainsAndNotContains() {
        CapturedQuery captured = runFindUnprocessed(req -> {
            req.setDocType("announcement");
            req.setTitleMatch(Map.of(
                    "mode", "contains",
                    "include", List.of("年度报告"),
                    "exclude", List.of("摘要", "英文版")
            ));
        });

        assertTrue(captured.sql.contains("(d.title LIKE ?)"));
        assertTrue(captured.sql.contains("NOT (d.title LIKE ? OR d.title LIKE ?)"));
        assertArrayEquals(new Object[]{"%年度报告%", "%摘要%", "%英文版%", 50, 0}, captured.params);
    }

    @Test
    void titleMatchIncludeModeAllUsesAnd() {
        CapturedQuery captured = runFindUnprocessed(req -> {
            req.setDocType("announcement");
            req.setTitleMatch(Map.of(
                    "mode", "contains",
                    "includeMode", "all",
                    "include", List.of("2024", "年度报告")
            ));
        });

        assertTrue(captured.sql.contains("(d.title LIKE ? AND d.title LIKE ?)"));
        assertArrayEquals(new Object[]{"%2024%", "%年度报告%", 50, 0}, captured.params);
    }

    @Test
    void titleMatchExcludeOnlyUsesNotContains() {
        CapturedQuery captured = runFindUnprocessed(req -> {
            req.setDocType("announcement");
            req.setTitleMatch(Map.of(
                    "mode", "contains",
                    "exclude", List.of("摘要")
            ));
        });

        assertTrue(captured.sql.contains("NOT (d.title LIKE ?)"));
        assertArrayEquals(new Object[]{"%摘要%", 50, 0}, captured.params);
    }

    @Test
    void titlePatternsAndTitleMatchAreMutuallyExclusive() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RagRecordService service = new RagRecordService(jdbcTemplate);
        RagRecordListRequest req = new RagRecordListRequest();
        req.setDocType("announcement");
        req.setTitlePatterns(List.of("年度报告"));
        req.setTitleMatch(Map.of("mode", "contains", "include", List.of("年度报告")));

        assertThrows(IllegalArgumentException.class, () -> service.findUnprocessed(req));
    }

    @Test
    void emptyTitlePatternsAndTitleMatchAreStillMutuallyExclusive() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RagRecordService service = new RagRecordService(jdbcTemplate);
        RagRecordListRequest req = new RagRecordListRequest();
        req.setDocType("announcement");
        req.setTitlePatterns(List.of());
        req.setTitleMatch(Map.of("mode", "contains", "include", List.of("年度报告")));

        assertThrows(IllegalArgumentException.class, () -> service.findUnprocessed(req));
    }

    @Test
    void titleMatchRejectsUnsupportedMode() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RagRecordService service = new RagRecordService(jdbcTemplate);
        RagRecordListRequest req = new RagRecordListRequest();
        req.setDocType("announcement");
        req.setTitleMatch(Map.of("mode", "regex", "include", List.of(".*年度报告")));

        assertThrows(IllegalArgumentException.class, () -> service.findUnprocessed(req));
    }

    @Test
    void titleMatchRejectsUnsupportedIncludeMode() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RagRecordService service = new RagRecordService(jdbcTemplate);
        RagRecordListRequest req = new RagRecordListRequest();
        req.setDocType("announcement");
        req.setTitleMatch(Map.of(
                "mode", "contains",
                "includeMode", "none",
                "include", List.of("年度报告")
        ));

        assertThrows(IllegalArgumentException.class, () -> service.findUnprocessed(req));
    }

    @Test
    void emptyTitleMatchRejects() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RagRecordService service = new RagRecordService(jdbcTemplate);
        RagRecordListRequest req = new RagRecordListRequest();
        req.setDocType("announcement");
        req.setTitleMatch(Map.of());

        assertThrows(IllegalArgumentException.class, () -> service.findUnprocessed(req));
    }

    private CapturedQuery runFindUnprocessed(RequestCustomizer customizer) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        RagRecordService service = new RagRecordService(jdbcTemplate);
        RagRecordListRequest req = new RagRecordListRequest();
        customizer.customize(req);

        service.findUnprocessed(req);

        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> paramsCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), paramsCaptor.capture());
        return new CapturedQuery(sqlCaptor.getValue(), paramsCaptor.getValue());
    }

    private interface RequestCustomizer {
        void customize(RagRecordListRequest req);
    }

    private record CapturedQuery(String sql, Object[] params) {
    }
}
