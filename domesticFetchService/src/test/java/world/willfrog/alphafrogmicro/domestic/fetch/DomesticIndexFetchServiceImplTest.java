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
