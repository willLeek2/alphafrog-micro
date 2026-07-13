package world.willfrog.alphafrogmicro.domestic.fetch.debug;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import world.willfrog.alphafrogmicro.common.dao.domestic.etf.EtfInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexQuoteDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexWeightDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryClassifyDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryMemberDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;

@Service
public class DomesticDebugQueryService {

    private static final int MAX_COUNT = 5;
    private static final String SW2021 = "SW2021";

    private final IndexWeightDao indexWeightDao;
    private final SwIndustryClassifyDao swIndustryClassifyDao;
    private final SwIndustryMemberDao swIndustryMemberDao;
    private final IndexQuoteDao indexQuoteDao;
    private final StockInfoDao stockInfoDao;
    private final EtfInfoDao etfInfoDao;
    private final TuShareRequestUtils tuShareRequestUtils;

    public DomesticDebugQueryService(IndexWeightDao indexWeightDao,
                                     SwIndustryClassifyDao swIndustryClassifyDao,
                                     SwIndustryMemberDao swIndustryMemberDao,
                                     IndexQuoteDao indexQuoteDao,
                                     StockInfoDao stockInfoDao,
                                     EtfInfoDao etfInfoDao,
                                     TuShareRequestUtils tuShareRequestUtils) {
        this.indexWeightDao = indexWeightDao;
        this.swIndustryClassifyDao = swIndustryClassifyDao;
        this.swIndustryMemberDao = swIndustryMemberDao;
        this.indexQuoteDao = indexQuoteDao;
        this.stockInfoDao = stockInfoDao;
        this.etfInfoDao = etfInfoDao;
        this.tuShareRequestUtils = tuShareRequestUtils;
    }

    public List<DebugAssetNameResponse> randomIndexConstituentStocks(String tsCode, int year, int count) {
        String normalizedTsCode = requireText(tsCode, "ts_code");
        int limit = requireCount(count);
        if (year < 1900 || year > 3000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "year must be between 1900 and 3000");
        }
        long startDate = year * 10000L + 101;
        long endDate = year * 10000L + 1231;
        return toAssetNames(indexWeightDao.getRandomConstituentStocksByIndexAndDateRange(
                normalizedTsCode, startDate, endDate, limit));
    }

    public List<String> randomSwL3IndustryNames(int count) {
        int limit = requireCount(count);
        List<String> names = nonBlank(swIndustryClassifyDao.getRandomL3IndustryNames(limit));
        if (names.size() >= limit) {
            return names;
        }
        List<String> memberNames = nonBlank(swIndustryMemberDao.getRandomL3IndustryNames(limit));
        names = mergeNames(names, memberNames, limit);
        if (names.size() >= limit) {
            return names;
        }
        return mergeNames(names, fetchSwL3IndustryNamesFromTushare(limit), limit);
    }

    public List<DebugAssetNameResponse> randomIndexNamesByAmountRange(String startDate,
                                                                      String endDate,
                                                                      double minAmount,
                                                                      int count) {
        int limit = requireCount(count);
        Long start = requireDate(startDate, "start_date");
        Long end = requireDate(endDate, "end_date");
        if (start > end) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "start_date must be <= end_date");
        }
        if (minAmount < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "min_amount must be >= 0");
        }
        return toAssetNames(indexQuoteDao.getRandomIndexNamesByAmountRange(start, end, minAmount, limit));
    }

    public List<DebugAssetNameResponse> randomListedStocks(int count) {
        return toAssetNames(stockInfoDao.getRandomListedStocks(requireCount(count)));
    }

    public List<DebugAssetNameResponse> randomListedEtfs(int count) {
        return toAssetNames(etfInfoDao.getRandomListedEtfs(requireCount(count)));
    }

    private int requireCount(int count) {
        if (count < 1 || count > MAX_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "count must be between 1 and 5");
        }
        return count;
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required");
        }
        return value.trim();
    }

    private Long requireDate(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("\\d{8}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be YYYYMMDD");
        }
        try {
            LocalDate.parse(normalized, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be a valid YYYYMMDD date");
        }
        return Long.parseLong(normalized);
    }

    private List<DebugAssetNameResponse> toAssetNames(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DebugAssetNameResponse> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String tsCode = stringValue(row, "ts_code");
            String name = stringValue(row, "name");
            if (!tsCode.isBlank()) {
                result.add(new DebugAssetNameResponse(tsCode, name.isBlank() ? tsCode : name));
            }
        }
        return result;
    }

    private String stringValue(Map<String, Object> row, String key) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        Object exact = row.get(key);
        if (exact != null) {
            return String.valueOf(exact).trim();
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                return String.valueOf(entry.getValue()).trim();
            }
        }
        return "";
    }

    private List<String> nonBlank(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                result.add(name.trim());
            }
        }
        return result;
    }

    private List<String> mergeNames(List<String> first, List<String> second, int limit) {
        Set<String> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        List<String> result = new ArrayList<>(merged);
        if (result.size() <= limit) {
            return result;
        }
        return result.subList(0, limit);
    }

    private List<String> fetchSwL3IndustryNamesFromTushare(int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("api_name", "index_classify");
        params.put("fields", "index_code,industry_name,parent_code,level,industry_code,is_pub");
        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("level", "L3");
        requestParams.put("src", SW2021);
        params.put("params", requestParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TuShare index_classify returned null");
        }
        Integer code = response.getInteger("code");
        if (code != null && code != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "TuShare index_classify failed: " + response.getString("msg"));
        }
        JSONObject data = response.getJSONObject("data");
        JSONArray fields = data == null ? null : data.getJSONArray("fields");
        JSONArray items = data == null ? null : data.getJSONArray("items");
        if (fields == null || items == null || fields.isEmpty() || items.isEmpty()) {
            return List.of();
        }
        int industryNameIndex = fields.indexOf("industry_name");
        if (industryNameIndex < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "TuShare index_classify response has no industry_name field");
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JSONArray item = items.getJSONArray(i);
            if (item == null || industryNameIndex >= item.size()) {
                continue;
            }
            String name = item.getString(industryNameIndex);
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        Collections.shuffle(names);
        return names.size() <= limit ? names : names.subList(0, limit);
    }
}
