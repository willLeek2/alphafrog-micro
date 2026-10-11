package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;


import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.domestic.fetch.TushareRequestTraceService;

@Component
@Slf4j
//@PropertySource("classpath:application.yml")
public class TuShareRequestUtils {

    @Value("${tushare.token}")
    private String tushareToken;

    private final TushareRequestTraceService traceService;


    public TuShareRequestUtils(TushareRequestTraceService traceService) {
        this.traceService = traceService;
    }

    public JSONObject createTusharePostRequest(Map<String, Object> params) {
        if (tushareToken == null || tushareToken.isBlank()) {
            log.error("TuShare token is not configured, aborting request");
            return null;
        }
        try ( CloseableHttpClient httpClient = HttpClients.createDefault() ) {
            HttpPost request = new HttpPost("http://api.tushare.pro");
            request.setHeader("Content-Type", "application/json");

            JSONObject jsonParams = new JSONObject();
            jsonParams.put("token", tushareToken);
            jsonParams.putAll(params);
            String jsonParamsString = jsonParams.toString();
            traceService.record(jsonParamsString);
            StringEntity entity = new StringEntity(jsonParamsString, ContentType.APPLICATION_JSON);
            request.setEntity(entity);

            long startMs = System.currentTimeMillis();
            try ( ClassicHttpResponse response = httpClient.execute(request) ) {
                HttpEntity responseEntity = response.getEntity();
                if (responseEntity != null) {
                    String responseBody = EntityUtils.toString(responseEntity);
                    long costMs = System.currentTimeMillis() - startMs;
                    if (response.getCode() != 200) {
                        log.warn("TuShare HTTP status not OK: status={} cost_ms={}", response.getCode(), costMs);
                        return null;
                    }
                    JSONObject responseJson = JSONObject.parseObject(responseBody);
                    if (responseJson == null) {
                        log.warn("TuShare response is not JSON");
                        return null;
                    }

                    Integer code = responseJson.getInteger("code");
                    String msg = responseJson.getString("msg");
                    // TuShare 以 code!=0 表示接口层错误（权限不足、参数非法、限流等）；
                    // 此时 items 为空，必须按失败返回 null，否则调用方会把错误当成「成功、0 行」。
                    if (code != null && code != 0) {
                        JSONObject dataObject = responseJson.getJSONObject("data");
                        JSONArray fetchedFields = dataObject == null ? null : dataObject.getJSONArray("fields");
                        JSONArray fetchedData = dataObject == null ? null : dataObject.getJSONArray("items");
                        int fieldsSize = fetchedFields == null ? 0 : fetchedFields.size();
                        int dataSize = fetchedData == null ? 0 : fetchedData.size();
                        log.warn("TuShare response code not zero: code={} msg={} fields_size={} items_size={} cost_ms={}",
                                code, msg, fieldsSize, dataSize, costMs);
                        return null;
                    }

                    return responseJson;
                } else {
                    log.warn("TuShare response entity is empty, status={}", response.getCode());
                    return null;
                }
            } catch (Exception e) {
                log.error("Error occurred while fetching data from TuShare!");
                log.error("jsonParamString: " + jsonParamsString);
                log.error("Error stack trace", e);
                return null;
            }


        } catch (Exception e) {
            log.error("Error building TuShare request", e);
            return null;
        }
    }
}
