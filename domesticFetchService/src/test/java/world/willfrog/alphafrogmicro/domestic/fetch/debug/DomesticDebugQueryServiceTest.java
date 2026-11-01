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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
