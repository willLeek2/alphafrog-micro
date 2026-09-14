package world.willfrog.alphafrogmicro.domestic.index;

import org.junit.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexQuoteDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexInfo;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexDailyByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexDailyByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountResponse;
import world.willfrog.alphafrogmicro.domestic.index.service.IndexDataCompletenessService;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void searchDomesticIndexFallback_shouldExposeHasDaily() {
        IndexInfo withDaily = indexInfo("000300.SH", "沪深300", 1);
        IndexInfo withoutDaily = indexInfo("399300.SZ", "沪深300全收益", 0);
        IndexInfoDao indexInfoDao = new FakeIndexInfoDao(List.of(withDaily, withoutDaily));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "test",
                Map.of("advanced.meili-enabled", "false")
        ));

        DomesticIndexServiceImpl service = new DomesticIndexServiceImpl(
                indexInfoDao,
                null,
                null,
                null,
                null,
                environment
        );

        DomesticIndexSearchResponse response = service.searchDomesticIndex(
                DomesticIndexSearchRequest.newBuilder().setQuery("沪深300").build()
        );

        assertEquals(2, response.getItemsCount());
        assertEquals("000300.SH", response.getItems(0).getTsCode());
        assertEquals(1, response.getItems(0).getHasDaily());
        assertEquals("399300.SZ", response.getItems(1).getTsCode());
        assertEquals(0, response.getItems(1).getHasDaily());
    }

    @Test
    public void getDomesticIndexDaily_shouldMarkMissingNumericFieldsWithoutThrowing() {
        IndexDaily row = new IndexDaily();
        row.setTsCode("931998.CSI");
        row.setTradeDate(1780329600000L);
        row.setClose(1000.0);
        row.setPreClose(990.0);
        row.setChange(10.0);
        row.setPctChg(1.01);
        row.setVol(null);
        row.setAmount(null);

        DomesticIndexServiceImpl service = new DomesticIndexServiceImpl(
                null,
                new FakeIndexQuoteDao(List.of(row)),
                null,
                completeCompletenessService(),
                null,
                null
        );

        DomesticIndexDailyByTsCodeAndDateRangeResponse response =
                service.getDomesticIndexDailyByTsCodeAndDateRange(
                        DomesticIndexDailyByTsCodeAndDateRangeRequest.newBuilder()
                                .setTsCode("931998.CSI")
                                .setStartDate(1L)
                                .setEndDate(2L)
                                .build()
                );

        assertEquals(1, response.getItemsCount());
        assertEquals("931998.CSI", response.getItems(0).getTsCode());
        assertEquals(1000.0, response.getItems(0).getClose(), 0.0001);
        assertFalse(response.getItems(0).hasOpen());
        assertFalse(response.getItems(0).hasHigh());
        assertFalse(response.getItems(0).hasLow());
        assertFalse(response.getItems(0).hasVol());
        assertFalse(response.getItems(0).hasAmount());
        assertTrue(response.getItems(0).getMissingFieldsList().contains("open"));
        assertTrue(response.getItems(0).getMissingFieldsList().contains("high"));
        assertTrue(response.getItems(0).getMissingFieldsList().contains("low"));
        assertTrue(response.getItems(0).getMissingFieldsList().contains("vol"));
        assertTrue(response.getItems(0).getMissingFieldsList().contains("amount"));
    }

    private static IndexInfo indexInfo(String tsCode, String name, int hasDaily) {
        IndexInfo indexInfo = new IndexInfo();
        indexInfo.setTsCode(tsCode);
        indexInfo.setName(name);
        indexInfo.setFullName(name);
        indexInfo.setMarket("SSE");
        indexInfo.setHasDaily(hasDaily);
        return indexInfo;
    }

    private static IndexDataCompletenessService completeCompletenessService() {
        return new IndexDataCompletenessService(null, null, null) {
            @Override
            public IndexCompletenessResult evaluate(String tsCode, long startDateTimestamp, long endDateTimestamp) {
                IndexCompletenessResult result = new IndexCompletenessResult();
                result.setTsCode(tsCode);
                result.setStartDate(startDateTimestamp);
                result.setEndDate(endDateTimestamp);
                result.setComplete(true);
                result.setMissingDates(List.of());
                return result;
            }
        };
    }

    private static final class FakeIndexInfoDao implements IndexInfoDao {
        private final List<IndexInfo> searchResult;

        private FakeIndexInfoDao(List<IndexInfo> searchResult) {
            this.searchResult = searchResult;
        }

        @Override
        public int insertIndexInfo(IndexInfo indexInfo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IndexInfo> getIndexInfoByTsCode(String tsCode, int limit, int offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IndexInfo> getIndexInfoByFullName(String fullName, int limit, int offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IndexInfo> getIndexInfoByName(String name, int limit, int offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IndexInfo> searchIndexInfo(String query, int limit, int offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IndexInfo> searchIndexInfoWithDaily(String query, int limit, int offset) {
            return searchResult;
        }

        @Override
        public int getIndexInfoCount() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> getAllIndexInfoTsCodes(int offset, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IndexInfo> getAllIndexInfo(int offset, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IndexInfo> getAllIndexInfoWithDaily(int offset, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeIndexQuoteDao implements IndexQuoteDao {
        private final List<IndexDaily> rows;

        private FakeIndexQuoteDao(List<IndexDaily> rows) {
            this.rows = rows;
        }

        @Override
        public int insertIndexDaily(IndexDaily indexDaily) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Long> getExistingTradeDates(String tsCode, Long startDate, Long endDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IndexDaily> getIndexDailiesByTsCodeAndDateRange(String tsCode, Long startDate, Long endDate) {
            return rows;
        }

        @Override
        public boolean hasAnyIndexDaily(String tsCode) {
            return !rows.isEmpty();
        }
    }
}
