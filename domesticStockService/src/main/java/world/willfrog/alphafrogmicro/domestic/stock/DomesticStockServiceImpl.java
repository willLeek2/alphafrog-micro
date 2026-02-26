package world.willfrog.alphafrogmicro.domestic.stock;

import lombok.extern.slf4j.Slf4j;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.SearchResult;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockQuoteDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockInfo;
import world.willfrog.alphafrogmicro.domestic.idl.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticStockServiceTriple.*;

import java.util.List;
import java.util.Map;

@Service
@DubboService
@Slf4j
public class DomesticStockServiceImpl extends DomesticStockServiceImplBase {

    private final StockInfoDao stockInfoDao;
    private final StockQuoteDao stockQuoteDao;

    @Value("${meilisearch.host:http://localhost:7700}")
    private String meiliHost;

    @Value("${meilisearch.api-key:alphafrog_search_key}")
    private String meiliApiKey;

    @Value("${advanced.meili-enabled:true}")
    private boolean meiliEnabled;

    public DomesticStockServiceImpl(StockInfoDao stockInfoDao,
                                    StockQuoteDao stockQuoteDao) {
        this.stockInfoDao = stockInfoDao;
        this.stockQuoteDao = stockQuoteDao;
    }

    @Override
    public DomesticStockInfoByTsCodeResponse getStockInfoByTsCode(DomesticStockInfoByTsCodeRequest request) {
        String tsCode = request.getTsCode();

        List<StockInfo> stockInfoList = stockInfoDao.getStockInfoByTsCode(tsCode, 10, 0);

        if (stockInfoList == null || stockInfoList.isEmpty()) {
            log.warn("StockInfo not found for tsCode: {}", tsCode);
            return DomesticStockInfoByTsCodeResponse.newBuilder().build();
        }

        StockInfo stockInfo = stockInfoList.get(0);
        DomesticStockInfoFullItem.Builder builder = DomesticStockInfoFullItem.newBuilder();

        builder.setTsCode(stockInfo.getTsCode()).setSymbol(stockInfo.getSymbol())
                .setStockInfoId(-1).setName(stockInfo.getName());

        if (stockInfo.getArea() != null) {
            builder.setMarket(stockInfo.getArea());
        }

        if (stockInfo.getIndustry() != null) {
            builder.setExchange(stockInfo.getIndustry());
        }

        if (stockInfo.getFullName() != null) {
            builder.setFullName(stockInfo.getFullName());
        }

        if (stockInfo.getEnName() != null) {
            builder.setEnName(stockInfo.getEnName());
        }

        if (stockInfo.getCnspell() != null) {
            builder.setCnspell(stockInfo.getCnspell());
        }

        if (stockInfo.getMarket() != null) {
            builder.setMarket(stockInfo.getMarket());
        }

        if (stockInfo.getExchange() != null) {
            builder.setExchange(stockInfo.getExchange());
        }

        if (stockInfo.getCurrType() != null) {
            builder.setCurrType(stockInfo.getCurrType());
        }

        if (stockInfo.getListStatus() != null) {
            builder.setListStatus(stockInfo.getListStatus());
        }

        if (stockInfo.getListDate() != null) {
            builder.setListDate(stockInfo.getListDate());
        }

        if (stockInfo.getDelistDate() != null) {
            builder.setDelistDate(stockInfo.getDelistDate());
        }

        if (stockInfo.getIsHs() != null) {
            builder.setIsHs(stockInfo.getIsHs());
        }

        if (stockInfo.getActName() != null) {
            builder.setActName(stockInfo.getActName());
        }

        if (stockInfo.getActEntType() != null) {
            builder.setActEntType(stockInfo.getActEntType());
        }

        DomesticStockInfoFullItem item = builder.build();

        return DomesticStockInfoByTsCodeResponse.newBuilder().setItem(item).build();
    }

    @Override
    public DomesticStockSearchResponse searchStock(DomesticStockSearchRequest request) {
        String query = request.getQuery();

        List<StockInfo> stockInfoList = stockInfoDao.getStockInfoByName(query, 10, 0);

        DomesticStockSearchResponse.Builder responseBuilder = DomesticStockSearchResponse.newBuilder();

        for (StockInfo stockInfo : stockInfoList) {
            DomesticStockInfoSimpleItem.Builder itemBuilder = DomesticStockInfoSimpleItem.newBuilder();
            itemBuilder.setTsCode(stockInfo.getTsCode())
                    .setSymbol(stockInfo.getSymbol())
                    .setName(stockInfo.getName())
                    .setArea(stockInfo.getArea() != null ? stockInfo.getArea() : "")
                    .setIndustry(stockInfo.getIndustry() != null ? stockInfo.getIndustry() : "");

            responseBuilder.addItems(itemBuilder.build());
        }

        return responseBuilder.build();
    }


    @Override
    public DomesticStockSearchESResponse searchStockES(DomesticStockSearchESRequest request) {
        if (!meiliEnabled) {
            return DomesticStockSearchESResponse.newBuilder().build();
        }

        String query = request.getQuery();
        DomesticStockSearchESResponse.Builder responseBuilder = DomesticStockSearchESResponse.newBuilder();
        try {
            Client client = new Client(new Config(meiliHost, meiliApiKey));
            Index index = client.index("stocks");
            SearchResult searchResult = (SearchResult) index.search(
                    SearchRequest.builder().q(query).limit(20).build()
            );
            for (Object hitObj : searchResult.getHits()) {
                if (!(hitObj instanceof Map<?, ?> hit)) {
                    continue;
                }
                DomesticStockInfoESItem.Builder itemBuilder = DomesticStockInfoESItem.newBuilder();
                itemBuilder.setTsCode(stringValue(hit.get("ts_code")))
                        .setSymbol(stringValue(hit.get("symbol")))
                        .setName(stringValue(hit.get("name")))
                        .setArea(stringValue(hit.get("area")))
                        .setIndustry(stringValue(hit.get("industry")));
                String enName = stringValue(hit.get("en_name"));
                if (!enName.isEmpty()) {
                    itemBuilder.setEnName(enName);
                }
                String fullName = stringValue(hit.get("full_name"));
                if (fullName.isEmpty()) {
                    fullName = stringValue(hit.get("fullname"));
                }
                if (!fullName.isEmpty()) {
                    itemBuilder.setFullName(fullName);
                }
                responseBuilder.addItems(itemBuilder.build());
            }
        } catch (Exception e) {
            log.warn("MeiliSearch query failed for stock search query={}", query, e);
        }
        return responseBuilder.build();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }


    @Override
    public DomesticStockTsCodeResponse getStockTsCode(DomesticStockTsCodeRequest request) {
        int offset = request.getOffset();
        int limit = request.getLimit();

        List<String> stockTsCodeList = stockInfoDao.getStockTsCode(offset, limit);

        DomesticStockTsCodeResponse.Builder responseBuilder = DomesticStockTsCodeResponse.newBuilder();

        for (String tsCode : stockTsCodeList) {
            responseBuilder.addTsCodes(tsCode);
        }

        return responseBuilder.build();
    }



    @Override
    public DomesticStockDailyByTsCodeAndDateRangeResponse getStockDailyByTsCodeAndDateRange(DomesticStockDailyByTsCodeAndDateRangeRequest request) {
        String tsCode = request.getTsCode();
        long startDate = request.getStartDate();
        long endDate = request.getEndDate();

        List<StockDaily> stockDailyList = stockQuoteDao.getStockDailyByTsCodeAndDateRange(tsCode, startDate, endDate);

        if (stockDailyList == null) {
            log.warn("StockDaily list is null for tsCode: {}, startDate: {}, endDate: {}", tsCode, startDate, endDate);
            return DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder().build();
        }

        DomesticStockDailyByTsCodeAndDateRangeResponse.Builder responseBuilder = DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder();

        for (StockDaily stockDaily : stockDailyList) {
            DomesticStockDailyItem.Builder itemBuilder = DomesticStockDailyItem.newBuilder();
            itemBuilder.setStockDailyId(-1)
                    .setTsCode(stockDaily.getTsCode())
                    .setTradeDate(stockDaily.getTradeDate())
                    .setClose(stockDaily.getClose())
                    .setOpen(stockDaily.getOpen())
                    .setHigh(stockDaily.getHigh())
                    .setLow(stockDaily.getLow())
                    .setPreClose(stockDaily.getPreClose())
                    .setChange(stockDaily.getChange())
                    .setPctChg(stockDaily.getPctChg())
                    .setVol(stockDaily.getVol())
                    .setAmount(stockDaily.getAmount());

            responseBuilder.addItems(itemBuilder.build());
        }

        return responseBuilder.build();
    }

    @Override
    public DomesticStockDailyByTradeDateResponse getStockDailyByTradeDate(DomesticStockDailyByTradeDateRequest request) {
        long tradeDate = request.getTradeDate();

        List<StockDaily> stockDailyList = stockQuoteDao.getStockDailyByTradeDate(tradeDate);

        DomesticStockDailyByTradeDateResponse.Builder responseBuilder = DomesticStockDailyByTradeDateResponse.newBuilder();

        for (StockDaily stockDaily : stockDailyList) {
            DomesticStockDailyItem.Builder itemBuilder = DomesticStockDailyItem.newBuilder();
            itemBuilder.setStockDailyId(-1)
                    .setTsCode(stockDaily.getTsCode())
                    .setTradeDate(stockDaily.getTradeDate())
                    .setClose(stockDaily.getClose())
                    .setOpen(stockDaily.getOpen())
                    .setHigh(stockDaily.getHigh())
                    .setLow(stockDaily.getLow())
                    .setPreClose(stockDaily.getPreClose())
                    .setChange(stockDaily.getChange())
                    .setPctChg(stockDaily.getPctChg())
                    .setVol(stockDaily.getVol())
                    .setAmount(stockDaily.getAmount());

            responseBuilder.addItems(itemBuilder.build());
        }

        return responseBuilder.build();
    }
}
