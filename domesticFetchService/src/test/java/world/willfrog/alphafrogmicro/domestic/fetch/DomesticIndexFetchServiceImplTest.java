package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexInfoDao;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.DomesticIndexStoreUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexDailyFetchAllByDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticindexDailyFetchAllByDateRangeRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomesticIndexFetchServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void fetchIndexDailyAllByDateRangeShouldSkipIndexesWithoutLocalDailyHistory() {
        TuShareRequestUtils tuShareRequestUtils = mock(TuShareRequestUtils.class);
        DomesticIndexStoreUtils domesticIndexStoreUtils = mock(DomesticIndexStoreUtils.class);
        IndexInfoDao indexInfoDao = mock(IndexInfoDao.class);

        when(indexInfoDao.getAllIndexInfoTsCodesWithDaily(0, 2))
                .thenReturn(List.of("000300.SH"));
        when(tuShareRequestUtils.createTusharePostRequest(any())).thenReturn(tuShareResponse());
        when(domesticIndexStoreUtils.storeIndexDailyByRawTuShareOutput(any(), any())).thenReturn(1);

        DomesticIndexFetchServiceImpl service = new DomesticIndexFetchServiceImpl(
                tuShareRequestUtils,
                domesticIndexStoreUtils,
                indexInfoDao
        );

        DomesticIndexDailyFetchAllByDateRangeResponse response =
                service.fetchDomesticIndexDailyAllByDateRange(
                        DomesticindexDailyFetchAllByDateRangeRequest.newBuilder()
                                .setStartDate(1780243200000L)
                                .setEndDate(1782230400000L)
                                .setIndexOffset(0)
                                .setIndexLimit(2)
                                .setOffset(0)
                                .setLimit(5000)
                                .build()
                );

        assertEquals("success", response.getStatus());
        assertEquals(1, response.getFetchedItemsCount());

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(tuShareRequestUtils, times(1)).createTusharePostRequest(paramsCaptor.capture());
        Map<String, Object> queryParams = (Map<String, Object>) paramsCaptor.getValue().get("params");
        assertEquals("000300.SH", queryParams.get("ts_code"));

        verify(indexInfoDao).getAllIndexInfoTsCodesWithDaily(0, 2);
        verify(indexInfoDao, never()).getAllIndexInfoTsCodes(0, 2);
    }

    private static JSONObject tuShareResponse() {
        JSONObject response = new JSONObject();
        JSONObject data = new JSONObject();
        JSONArray fields = new JSONArray();
        fields.add("ts_code");
        fields.add("trade_date");
        JSONArray row = new JSONArray();
        row.add("000300.SH");
        row.add("20260601");
        JSONArray items = new JSONArray();
        items.add(row);
        data.put("fields", fields);
        data.put("items", items);
        response.put("data", data);
        return response;
    }
}
