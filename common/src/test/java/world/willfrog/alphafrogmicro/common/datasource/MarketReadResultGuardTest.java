package world.willfrog.alphafrogmicro.common.datasource;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketReadResultGuardTest {

    private MarketReadResultGuard guardWith(int maxRows, long maxBytes, int defaultPageSize, int maxPageSize) {
        MarketReadDataSourceProperties.Limits limits = new MarketReadDataSourceProperties.Limits();
        limits.setMaxRows(maxRows);
        limits.setMaxBytes(maxBytes);
        limits.setDefaultPageSize(defaultPageSize);
        limits.setMaxPageSize(maxPageSize);
        return new MarketReadResultGuard(limits);
    }

    @Test
    void checkRows_shouldThrowOnRowOverflow_andNotTruncate() {
        MarketReadResultGuard guard = guardWith(2, 10_000, 100, 1000);
        List<String> rows = List.of("a", "b", "c");
        MarketReadResultLimitExceededException ex = assertThrows(
                MarketReadResultLimitExceededException.class, () -> guard.checkRows(rows));
        assertEquals(MarketReadResultLimitExceededException.ERROR_CODE, ex.getErrorCode());
        assertEquals(MarketReadResultLimitExceededException.LimitKind.ROWS, ex.getKind());
        assertEquals(3, ex.getActual());
        assertEquals(2, ex.getLimit());
        assertEquals(3, rows.size());
    }

    @Test
    void checkRows_shouldThrowOnByteOverflow_andNotTruncate() {
        MarketReadResultGuard guard = guardWith(10, 3, 100, 1000);
        List<String> rows = new ArrayList<>(List.of("ab", "cd"));
        MarketReadResultLimitExceededException ex = assertThrows(
                MarketReadResultLimitExceededException.class, () -> guard.checkRows(rows));
        assertEquals(MarketReadResultLimitExceededException.ERROR_CODE, ex.getErrorCode());
        assertEquals(MarketReadResultLimitExceededException.LimitKind.BYTES, ex.getKind());
        assertEquals(2, rows.size());
    }

    @Test
    void checkRows_shouldReturnSameListWhenWithinLimits() {
        MarketReadResultGuard guard = guardWith(2, 10_000, 100, 1000);
        List<String> rows = List.of("a", "b");
        assertSame(rows, guard.checkRows(rows));
    }

    @Test
    void resolvePage_shouldUseDefaultsAndComputeOffset() {
        MarketReadResultGuard guard = guardWith(10_000, 1_048_576, 100, 1000);
        MarketReadPage first = guard.resolvePage(null, null);
        assertEquals(1, first.page());
        assertEquals(100, first.pageSize());
        assertEquals(0, first.offset());
        assertEquals(100, first.limit());

        MarketReadPage second = guard.resolvePage(2, 50);
        assertEquals(50, second.offset());
        assertEquals(50, second.limit());
    }

    @Test
    void resolvePage_shouldRejectSizeAboveMaxPageSize() {
        MarketReadResultGuard guard = guardWith(10_000, 1_048_576, 100, 100);
        MarketReadResultLimitExceededException ex = assertThrows(
                MarketReadResultLimitExceededException.class, () -> guard.resolvePage(1, 101));
        assertEquals(MarketReadResultLimitExceededException.ERROR_CODE, ex.getErrorCode());
        assertEquals(MarketReadResultLimitExceededException.LimitKind.PAGE_SIZE, ex.getKind());
        assertEquals(101, ex.getActual());
        assertEquals(100, ex.getLimit());
    }

    @Test
    void resolvePage_shouldAllowExactMaxPageSize() {
        MarketReadResultGuard guard = guardWith(10_000, 1_048_576, 100, 100);
        MarketReadPage page = guard.resolvePage(1, 100);
        assertEquals(100, page.limit());
        assertEquals(0, page.offset());
    }

    @Test
    void checkPage_exactLimit_hasNoMore() {
        MarketReadResultGuard guard = guardWith(10_000, 1_048_576, 2, 2);
        MarketReadPage page = guard.resolvePage(1, 2);
        MarketReadPageResult<String> result = guard.checkPage(List.of("a", "b"), page);
        assertEquals(List.of("a", "b"), result.rows());
        assertFalse(result.hasMore());
        assertEquals(0, result.offset());
        assertEquals(2, result.limit());
    }

    @Test
    void checkPage_probeRow_setsHasMore_andKeepsPageSize() {
        MarketReadResultGuard guard = guardWith(10_000, 1_048_576, 2, 2);
        MarketReadPage page = guard.resolvePage(1, 2);
        MarketReadPageResult<String> result = guard.checkPage(List.of("a", "b", "c"), page);
        assertEquals(List.of("a", "b"), result.rows());
        assertTrue(result.hasMore());
    }

    @Test
    void checkPage_shouldThrowWhenCallerDidNotApplyLimit() {
        MarketReadResultGuard guard = guardWith(10_000, 1_048_576, 2, 2);
        MarketReadPage page = guard.resolvePage(1, 2);
        List<String> fetched = List.of("a", "b", "c", "d");
        MarketReadResultLimitExceededException ex = assertThrows(
                MarketReadResultLimitExceededException.class, () -> guard.checkPage(fetched, page));
        assertEquals(MarketReadResultLimitExceededException.ERROR_CODE, ex.getErrorCode());
        assertEquals(4, fetched.size());
    }

    @Test
    void checkRows_shouldCountFullJsonIncludingQuotesAndArray() throws Exception {
        MarketReadResultGuard guard = guardWith(10, 8, 100, 1000);
        List<String> rows = List.of("a", "b");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(9, mapper.writeValueAsBytes(rows).length);
        MarketReadResultLimitExceededException ex = assertThrows(
                MarketReadResultLimitExceededException.class, () -> guard.checkRows(rows));
        assertEquals(MarketReadResultLimitExceededException.LimitKind.BYTES, ex.getKind());
        assertEquals(9, ex.getActual());
        assertEquals(8, ex.getLimit());
        assertEquals(2, rows.size());
    }

    @Test
    void checkRows_shouldCountJsonEscapeOverhead() throws Exception {
        MarketReadResultGuard guard = guardWith(10, 4, 100, 1000);
        List<String> rows = List.of("\"");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        long jsonBytes = mapper.writeValueAsBytes(rows).length;
        assertTrue(jsonBytes > 4);
        MarketReadResultLimitExceededException ex = assertThrows(
                MarketReadResultLimitExceededException.class, () -> guard.checkRows(rows));
        assertEquals(MarketReadResultLimitExceededException.LimitKind.BYTES, ex.getKind());
        assertEquals(jsonBytes, ex.getActual());
    }

    @Test
    void checkRows_customEstimator_shouldRejectSaturatedOverflow() {
        MarketReadResultGuard guard = guardWith(10, Long.MAX_VALUE, 100, 1000);
        List<String> rows = List.of("x", "y");
        MarketReadResultLimitExceededException ex = assertThrows(
                MarketReadResultLimitExceededException.class,
                () -> guard.checkRows(rows, row -> Long.MAX_VALUE / 2 + 10));
        assertEquals(MarketReadResultLimitExceededException.ERROR_CODE, ex.getErrorCode());
        assertEquals(MarketReadResultLimitExceededException.LimitKind.BYTES, ex.getKind());
        assertEquals(Long.MAX_VALUE, ex.getActual());
        assertEquals(2, rows.size());
    }

    @Test
    void checkRows_customEstimator_shouldAllowExactMaxBytes() {
        MarketReadResultGuard guard = guardWith(10, 10, 100, 1000);
        List<String> rows = List.of("x", "y");
        assertSame(rows, guard.checkRows(rows, row -> 5L));
    }

    @Test
    void checkPage_shouldCountFullJsonOfReturnedPage() throws Exception {
        MarketReadResultGuard guard = guardWith(10_000, 20, 2, 2);
        MarketReadPage page = guard.resolvePage(1, 2);
        List<String> rows = List.of("a", "b");
        MarketReadPageResult<String> wouldReturn = new MarketReadPageResult<>(rows, 0L, 2, false);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        long jsonBytes = mapper.writeValueAsBytes(wouldReturn).length;
        assertTrue(jsonBytes > 20);
        MarketReadResultLimitExceededException ex = assertThrows(
                MarketReadResultLimitExceededException.class, () -> guard.checkPage(rows, page));
        assertEquals(MarketReadResultLimitExceededException.LimitKind.BYTES, ex.getKind());
        assertEquals(jsonBytes, ex.getActual());
    }

    @Test
    void resolvePage_largePage_shouldKeepPositiveLongOffset() {
        MarketReadResultGuard guard = guardWith(10_000, 1_048_576, 100, 1000);
        MarketReadPage page = guard.resolvePage(Integer.MAX_VALUE, 1000);
        assertEquals(2_147_483_646_000L, page.offset());
        assertTrue(page.offset() > 0);
        MarketReadPageResult<String> result = new MarketReadPageResult<>(List.of(), page.offset(), page.limit(), false);
        assertEquals(2_147_483_646_000L, result.offset());
    }
}
