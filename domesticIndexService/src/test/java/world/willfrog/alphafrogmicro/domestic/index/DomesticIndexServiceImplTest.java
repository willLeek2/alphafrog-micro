package world.willfrog.alphafrogmicro.domestic.index;

import org.junit.Test;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountResponse;

import static org.junit.Assert.assertEquals;

public class DomesticIndexServiceImplTest {

    @Test
    public void getTradingDaysCountByDateRange_shouldNotSwapInvalidRange() {
        DomesticIndexServiceImpl service = new DomesticIndexServiceImpl(
                null,
                null,
                null,
                null,
                null,
                null
        );

        DomesticTradingDaysCountResponse response = service.getTradingDaysCountByDateRange(
                DomesticTradingDaysCountRequest.newBuilder()
                        .setExchange("SSE")
                        .setStartDate(20260101L)
                        .setEndDate(20250101L)
                        .build()
        );

        assertEquals("SSE", response.getExchange());
        assertEquals(20260101L, response.getStartDate());
        assertEquals(20250101L, response.getEndDate());
        assertEquals(0, response.getTradingDaysCount());
        assertEquals(0L, response.getFirstTradingDate());
        assertEquals(0L, response.getLastTradingDate());
    }
}
