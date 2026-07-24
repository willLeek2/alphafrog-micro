package world.willfrog.alphafrogmicro.domestic.fetch.debug;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import world.willfrog.alphafrogmicro.common.dao.domestic.etf.EtfInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexQuoteDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexWeightDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryClassifyDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryMemberDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomesticDebugQueryServiceTest {

    private final IndexWeightDao indexWeightDao = mock(IndexWeightDao.class);
    private final SwIndustryClassifyDao swIndustryClassifyDao = mock(SwIndustryClassifyDao.class);
    private final SwIndustryMemberDao swIndustryMemberDao = mock(SwIndustryMemberDao.class);
    private final IndexQuoteDao indexQuoteDao = mock(IndexQuoteDao.class);
    private final StockInfoDao stockInfoDao = mock(StockInfoDao.class);
    private final EtfInfoDao etfInfoDao = mock(EtfInfoDao.class);
    private final TuShareRequestUtils tuShareRequestUtils = mock(TuShareRequestUtils.class);

    private final DomesticDebugQueryService service = new DomesticDebugQueryService(
            indexWeightDao,
            swIndustryClassifyDao,
            swIndustryMemberDao,
            indexQuoteDao,
            stockInfoDao,
            etfInfoDao,
            tuShareRequestUtils
    );

    @Test
    void randomIndexConstituentStocksShouldQueryLatestDistinctConstituentsWithinYear() {
        when(indexWeightDao.getRandomConstituentStocksByIndexAndDateRange("000300.SH", 20250101L, 20251231L, 2))
                .thenReturn(List.of(
                        Map.of("ts_code", "600000.SH", "name", "浦发银行"),
                        Map.of("TS_CODE", "000001.SZ", "NAME", "")
                ));

        List<DebugAssetNameResponse> result =
                service.randomIndexConstituentStocks(" 000300.SH ", 2025, 2);

        assertEquals(List.of(
                new DebugAssetNameResponse("600000.SH", "浦发银行"),
                new DebugAssetNameResponse("000001.SZ", "000001.SZ")
        ), result);
    }

    @Test
    void randomIndexConstituentStocksShouldRejectInvalidCountAndYear() {
        assertBadRequest(() -> service.randomIndexConstituentStocks("000300.SH", 2025, 0));
        assertBadRequest(() -> service.randomIndexConstituentStocks("000300.SH", 1899, 1));
        assertBadRequest(() -> service.randomIndexConstituentStocks(" ", 2025, 1));
    }

    @Test
    void randomSwL3IndustryNamesShouldUseClassifyThenMemberThenTushareFallback() {
        when(swIndustryClassifyDao.getRandomL3IndustryNames(4)).thenReturn(List.of("半导体", " "));
        when(swIndustryMemberDao.getRandomL3IndustryNames(4)).thenReturn(Arrays.asList("半导体", "白酒", null));
        when(tuShareRequestUtils.createTusharePostRequest(any())).thenReturn(tuShareIndustryResponse());

        List<String> result = service.randomSwL3IndustryNames(4);

        assertEquals(4, result.size());
        assertEquals(List.of("半导体", "白酒"), result.subList(0, 2));
        assertTrue(result.contains("消费电子"));
        assertTrue(result.contains("软件开发"));

        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(tuShareRequestUtils).createTusharePostRequest(requestCaptor.capture());
        Map<String, Object> request = requestCaptor.getValue();
        assertEquals("index_classify", request.get("api_name"));
        assertEquals("index_code,industry_name,parent_code,level,industry_code,is_pub", request.get("fields"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        assertEquals("L3", params.get("level"));
        assertEquals("SW2021", params.get("src"));
    }

    @Test
    void randomSwL3IndustryNamesShouldNotCallTushareWhenClassifyHasEnoughNames() {
        when(swIndustryClassifyDao.getRandomL3IndustryNames(2)).thenReturn(List.of("半导体", "白酒"));

        List<String> result = service.randomSwL3IndustryNames(2);

        assertEquals(List.of("半导体", "白酒"), result);
        verify(swIndustryMemberDao, never()).getRandomL3IndustryNames(2);
        verify(tuShareRequestUtils, never()).createTusharePostRequest(any());
    }

    @Test
    void randomIndexNamesByAmountRangeShouldConvertYyyyMmDdToShanghaiEpochMilliseconds() {
        when(indexQuoteDao.getRandomIndexNamesByAmountRange(
                1735660800000L, 1767110400000L, 100000.0, 2))
                .thenReturn(List.of(
                        Map.of("ts_code", "000300.SH", "name", "沪深300",
                                "full_name", "沪深300指数"),
                        Map.of("ts_code", "000905.SH", "name", "中证500",
                                "full_name", "中证小盘500指数")
                ));

        List<DebugAssetNameResponse> result =
                service.randomIndexNamesByAmountRange("20250101", "20251231", 100000.0, 2);

        assertEquals(List.of(
                new DebugAssetNameResponse("000300.SH", "沪深300", "沪深300指数"),
                new DebugAssetNameResponse("000905.SH", "中证500", "中证小盘500指数")
        ), result);
        verify(indexQuoteDao).getRandomIndexNamesByAmountRange(
                1735660800000L, 1767110400000L, 100000.0, 2);
    }

    @Test
    void randomIndexNamesByAmountRangeShouldDropRowsWithoutChineseNameOrFullName() {
        when(indexQuoteDao.getRandomIndexNamesByAmountRange(
                1735660800000L, 1767110400000L, 100000.0, 5))
                .thenReturn(List.of(
                        Map.of("ts_code", "000300.SH", "name", "沪深300",
                                "full_name", "沪深300指数"),
                        Map.of("ts_code", "H21118.CSI", "name", "H21118.CSI",
                                "full_name", "H21118.CSI"),
                        Map.of("ts_code", "930997.CSI", "name", "CSSW电子",
                                "full_name", "中证申万电子主题指数"),
                        Map.of("ts_code", "000905.SH", "name", "中证500",
                                "full_name", " "),
                        Map.of("ts_code", "931643.CSI", "name", " ",
                                "full_name", "中证龙头指数")
                ));

        List<DebugAssetNameResponse> result =
                service.randomIndexNamesByAmountRange("20250101", "20251231", 100000.0, 5);

        assertEquals(List.of(
                new DebugAssetNameResponse("000300.SH", "沪深300", "沪深300指数"),
                new DebugAssetNameResponse("930997.CSI", "CSSW电子", "中证申万电子主题指数")
        ), result);
    }

    @Test
    void debugAssetNameResponseShouldSerializeFullNameAsSnakeCase() throws Exception {
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(
                new DebugAssetNameResponse("000300.SH", "沪深300", "沪深300指数")));

        assertEquals("沪深300指数", json.get("full_name").asText());
        assertTrue(!json.has("fullName"));
    }

    @Test
    void randomIndexNamesByAmountRangeShouldRejectInvalidRangeOrAmount() {
        assertBadRequest(() -> service.randomIndexNamesByAmountRange("20250132", "20251231", 1.0, 1));
        assertBadRequest(() -> service.randomIndexNamesByAmountRange("20251231", "20250101", 1.0, 1));
        assertBadRequest(() -> service.randomIndexNamesByAmountRange("20250101", "20251231", -1.0, 1));
    }

    @Test
    void randomListedStocksShouldReturnActiveStockNamesFromDao() {
        when(stockInfoDao.getRandomListedStocks(2)).thenReturn(List.of(
                Map.of("ts_code", "600519.SH", "name", "贵州茅台"),
                Map.of("ts_code", "000001.SZ", "name", "平安银行")
        ));

        List<DebugAssetNameResponse> result = service.randomListedStocks(2);

        assertEquals(List.of(
                new DebugAssetNameResponse("600519.SH", "贵州茅台"),
                new DebugAssetNameResponse("000001.SZ", "平安银行")
        ), result);
    }

    @Test
    void randomListedEtfsShouldReturnActiveEtfNamesFromDao() {
        when(etfInfoDao.getRandomListedEtfs(2)).thenReturn(List.of(
                Map.of("ts_code", "510300.SH", "name", "沪深300ETF"),
                Map.of("ts_code", "159915.SZ", "name", "创业板ETF")
        ));

        List<DebugAssetNameResponse> result = service.randomListedEtfs(2);

        assertEquals(List.of(
                new DebugAssetNameResponse("510300.SH", "沪深300ETF"),
                new DebugAssetNameResponse("159915.SZ", "创业板ETF")
        ), result);
    }

    @Test
    void randomListedAssetsShouldRejectOutOfRangeCountBeforeQueryingDao() {
        assertBadRequest(() -> service.randomListedStocks(0));
        assertBadRequest(() -> service.randomListedEtfs(6));
        verify(stockInfoDao, never()).getRandomListedStocks(anyInt());
        verify(etfInfoDao, never()).getRandomListedEtfs(anyInt());
    }

    private static void assertBadRequest(Runnable runnable) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, runnable::run);
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private static JSONObject tuShareIndustryResponse() {
        JSONObject response = new JSONObject();
        response.put("code", 0);
        JSONObject data = new JSONObject();
        JSONArray fields = new JSONArray();
        fields.add("index_code");
        fields.add("industry_name");
        JSONArray items = new JSONArray();
        JSONArray row1 = new JSONArray();
        row1.add("850831.SI");
        row1.add("消费电子");
        JSONArray row2 = new JSONArray();
        row2.add("850721.SI");
        row2.add("软件开发");
        items.add(row1);
        items.add(row2);
        data.put("fields", fields);
        data.put("items", items);
        response.put("data", data);
        return response;
    }
}
