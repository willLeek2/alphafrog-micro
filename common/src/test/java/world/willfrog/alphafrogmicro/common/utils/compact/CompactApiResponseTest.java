package world.willfrog.alphafrogmicro.common.utils.compact;

import org.junit.Test;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactApiResponse;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactMeta;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * CompactApiResponse单元测试
 */
public class CompactApiResponseTest {
    
    @Test
    public void testBasicConstruction() {
        List<String> fields = Arrays.asList("ts_code", "trade_date", "close");
        List<List<Object>> rows = Arrays.asList(
            Arrays.asList("000001.SZ", 1640995200000L, 15.23),
            Arrays.asList("000002.SZ", 1640995200000L, 18.45)
        );
        
        CompactApiResponse response = CompactApiResponse.of(fields, rows);
        
        assertNotNull(response);
        assertEquals("compact", response.getFormat());
        assertEquals(fields, response.getFields());
        assertEquals(rows, response.getRows());
        assertNull(response.getMeta());
    }
    
    @Test
    public void testConstructionWithMeta() {
        List<String> fields = Arrays.asList("ts_code", "trade_date", "close");
        List<List<Object>> rows = Arrays.asList(
            Arrays.asList("000001.SZ", 1640995200000L, 15.23)
        );
        
        CompactMeta meta = CompactMeta.builder()
                .tsCode("000001.SZ")
                .total(100L)
                .page(1)
                .pageSize(10)
                .build();
        
        CompactApiResponse response = CompactApiResponse.of(fields, rows, meta);
        
        assertNotNull(response);
        assertEquals("compact", response.getFormat());
        assertEquals(fields, response.getFields());
        assertEquals(rows, response.getRows());
        assertNotNull(response.getMeta());
        assertEquals("000001.SZ", response.getMeta().getTsCode());
        assertEquals(Long.valueOf(100), response.getMeta().getTotal());
    }
    
    
    @Test
    public void testEmptyResponse() {
        CompactApiResponse response = CompactApiResponse.of(
            Arrays.asList(), 
            Arrays.asList()
        );
        
        assertNotNull(response);
        assertEquals("compact", response.getFormat());
        assertTrue(response.getFields().isEmpty());
        assertTrue(response.getRows().isEmpty());
    }
}