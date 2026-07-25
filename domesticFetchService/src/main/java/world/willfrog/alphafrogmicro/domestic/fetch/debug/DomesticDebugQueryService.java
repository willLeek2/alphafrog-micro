package world.willfrog.alphafrogmicro.domestic.fetch.debug;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import world.willfrog.alphafrogmicro.common.dao.domestic.etf.EtfInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexQuoteDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexWeightDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryClassifyDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryMemberDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;

@Service
public class DomesticDebugQueryService {

    private static final int MAX_COUNT = 5;
    private static final int TRADING_DAYS_PER_YEAR = 250;
    private static final int MIN_DAILY_COVERAGE_PERCENT = 90;
    private static final int MAX_CANDIDATE_COUNT = 100;
    private static final int MAX_SAMPLE_ATTEMPTS = 20;
    private static final List<String> NON_CNY_CURRENCY_CODES = List.of(
            "HKD", "USD", "EUR", "CNH", "SGD", "AUD", "GBP",
            "JPY", "CAD", "CHF", "KRW", "TWD", "MOP");
    private static final List<String> NON_CNY_CURRENCY_NAMES = List.of(
            "港元", "港币", "美元", "欧元", "离岸人民币", "新加坡元", "新币",
            "澳元", "英镑", "日元", "加元", "瑞士法郎", "韩元",
            "台币", "新台币", "澳门元", "澳门币");
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

    public DebugAssetSampleResponse randomIndexNamesByCoverage(int startYear,
                                                                int endYear,
                                                                Double minAverageAmount,
                                                                boolean cnyOnly,
                                                                int candidateCount,
                                                                int maxAttempts,
                                                                int count) {
        YearRange range = requireYearRange(startYear, endYear);
        Double amountThreshold = requireAmountThreshold(minAverageAmount);
        return sampleEligibleAssets(
                count,
                candidateCount,
                maxAttempts,
                range,
                limit -> toIndexNames(indexQuoteDao.getEligibleRandomIndices(
                        range.startTimestamp(),
                        range.endTimestamp(),
                        range.requiredDailyCount(),
                        amountThreshold,
                        limit), cnyOnly));
    }

    public DebugAssetSampleResponse randomListedStocks(int startYear,
                                                       int endYear,
                                                       Double minAverageAmount,
                                                       int candidateCount,
                                                       int maxAttempts,
                                                       int count) {
        YearRange range = requireYearRange(startYear, endYear);
        Double amountThreshold = requireAmountThreshold(minAverageAmount);
        return sampleEligibleAssets(
                count,
                candidateCount,
                maxAttempts,
                range,
                limit -> toAssetNames(stockInfoDao.getEligibleRandomStocks(
                        range.startTimestamp(),
                        range.endTimestamp(),
                        range.requiredDailyCount(),
                        amountThreshold,
                        limit)));
    }

    public DebugAssetSampleResponse randomListedEtfs(int startYear,
                                                     int endYear,
                                                     Double minAverageAmount,
                                                     int candidateCount,
                                                     int maxAttempts,
                                                     int count) {
        YearRange range = requireYearRange(startYear, endYear);
        Double amountThreshold = requireAmountThreshold(minAverageAmount);
        return sampleEligibleAssets(
                count,
                candidateCount,
                maxAttempts,
                range,
                limit -> toAssetNames(etfInfoDao.getEligibleRandomEtfs(
                        range.startTimestamp(),
                        range.endTimestamp(),
                        range.requiredDailyCount(),
                        amountThreshold,
                        limit)));
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

    private YearRange requireYearRange(int startYear, int endYear) {
        if (startYear < 1900 || startYear > 3000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "start_year must be between 1900 and 3000");
        }
        if (endYear < 1900 || endYear > 3000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "end_year must be between 1900 and 3000");
        }
        if (startYear > endYear) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "start_year must be <= end_year");
        }
        LocalDate startDate = LocalDate.of(startYear, 1, 1);
        LocalDate endDate = LocalDate.of(endYear, 12, 31);
        int yearCount = endYear - startYear + 1;
        int idealDailyCount = Math.multiplyExact(yearCount, TRADING_DAYS_PER_YEAR);
        int requiredDailyCount = (idealDailyCount * MIN_DAILY_COVERAGE_PERCENT + 99) / 100;
        return new YearRange(
                startDate.format(DateTimeFormatter.BASIC_ISO_DATE),
                endDate.format(DateTimeFormatter.BASIC_ISO_DATE),
                DateConvertUtils.convertLocalDateToMsTimestamp(startDate),
                DateConvertUtils.convertLocalDateToMsTimestamp(endDate),
                idealDailyCount,
                requiredDailyCount);
    }

    private Double requireAmountThreshold(Double minAverageAmount) {
        if (minAverageAmount == null) {
            return null;
        }
        if (!Double.isFinite(minAverageAmount) || minAverageAmount < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "min_avg_amount must be a finite number >= 0");
        }
        return minAverageAmount;
    }

    private DebugAssetSampleResponse sampleEligibleAssets(
            int count,
            int candidateCount,
            int maxAttempts,
            YearRange range,
            IntFunction<List<DebugAssetNameResponse>> candidateLoader) {
        int limit = requireCount(count);
        int validatedCandidateCount = requireCandidateCount(candidateCount);
        int validatedMaxAttempts = requireMaxAttempts(maxAttempts);
        Map<String, DebugAssetNameResponse> collected = new LinkedHashMap<>();
        int attempts = 0;

        while (attempts < validatedMaxAttempts && collected.size() < limit) {
            attempts++;
            List<DebugAssetNameResponse> candidates = candidateLoader.apply(validatedCandidateCount);
            if (candidates == null) {
                continue;
            }
            for (DebugAssetNameResponse candidate : candidates) {
                if (candidate == null || candidate.tsCode() == null || candidate.tsCode().isBlank()) {
                    continue;
                }
                collected.putIfAbsent(candidate.tsCode(), candidate);
                if (collected.size() >= limit) {
                    break;
                }
            }
        }

        if (collected.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "no asset satisfies the requested daily coverage and average amount filters");
        }

        List<DebugAssetNameResponse> items = new ArrayList<>(collected.values());
        if (items.size() > limit) {
            items = items.subList(0, limit);
        }
        String status = items.size() == limit ? "complete" : "partial";
        return new DebugAssetSampleResponse(
                status,
                limit,
                items.size(),
                validatedCandidateCount,
                attempts,
                validatedMaxAttempts,
                range.startDate(),
                range.endDate(),
                range.idealDailyCount(),
                range.requiredDailyCount(),
                items);
    }

    private int requireCandidateCount(int candidateCount) {
        if (candidateCount < 1 || candidateCount > MAX_CANDIDATE_COUNT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "candidate_count must be between 1 and " + MAX_CANDIDATE_COUNT);
        }
        return candidateCount;
    }

    private int requireMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > MAX_SAMPLE_ATTEMPTS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "max_attempts must be between 1 and " + MAX_SAMPLE_ATTEMPTS);
        }
        return maxAttempts;
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
                result.add(new DebugAssetNameResponse(
                        tsCode,
                        name.isBlank() ? tsCode : name,
                        null,
                        longValue(row, "daily_count"),
                        doubleValue(row, "average_amount")));
            }
        }
        return result;
    }

    private List<DebugAssetNameResponse> toIndexNames(
            List<Map<String, Object>> rows,
            boolean cnyOnly) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DebugAssetNameResponse> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String tsCode = stringValue(row, "ts_code");
            String name = stringValue(row, "name");
            String fullName = stringValue(row, "full_name");
            // DAO 已先过滤；这里再次校验，避免调试接口把代码或空名称暴露给数据生成器。
            if (!tsCode.isBlank()
                    && hasChineseText(name)
                    && hasChineseText(fullName)
                    && (!cnyOnly || hasNoNonCnyMarker(name, fullName))) {
                result.add(new DebugAssetNameResponse(
                        tsCode,
                        name,
                        fullName,
                        longValue(row, "daily_count"),
                        doubleValue(row, "average_amount")));
            }
        }
        return result;
    }

    private boolean hasNoNonCnyMarker(String name, String fullName) {
        return !hasNonCnyMarker(name) && !hasNonCnyMarker(fullName);
    }

    private boolean hasNonCnyMarker(String value) {
        if (value == null) {
            return false;
        }
        String upperValue = value.toUpperCase(Locale.ROOT);
        return NON_CNY_CURRENCY_CODES.stream().anyMatch(upperValue::contains)
                || NON_CNY_CURRENCY_NAMES.stream().anyMatch(value::contains);
    }

    private boolean hasChineseText(String value) {
        return value != null
                && !value.isBlank()
                && value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
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

    private Long longValue(Map<String, Object> row, String key) {
        Object value = mapValue(row, key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double doubleValue(Map<String, Object> row, String key) {
        Object value = mapValue(row, key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Object mapValue(Map<String, Object> row, String key) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object exact = row.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
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

    private record YearRange(
            String startDate,
            String endDate,
            long startTimestamp,
            long endTimestamp,
            int idealDailyCount,
            int requiredDailyCount) {
    }
}
