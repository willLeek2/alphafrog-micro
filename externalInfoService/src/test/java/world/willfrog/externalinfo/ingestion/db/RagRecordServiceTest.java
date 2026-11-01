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
