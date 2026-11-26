package world.willfrog.alphafrogmicro.domestic.fetch.debug;

import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import world.willfrog.alphafrogmicro.domestic.idl.DebugAssetName;
import world.willfrog.alphafrogmicro.domestic.idl.DebugAssetSample;
import world.willfrog.alphafrogmicro.domestic.idl.DebugSampleStatus;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticDebugSampleServiceTriple.DomesticDebugSampleServiceImplBase;
import world.willfrog.alphafrogmicro.domestic.idl.RandomIndexConstituentsRequest;
import world.willfrog.alphafrogmicro.domestic.idl.RandomIndexConstituentsResponse;
import world.willfrog.alphafrogmicro.domestic.idl.RandomIndexNamesByAmountRequest;
import world.willfrog.alphafrogmicro.domestic.idl.RandomIndexNamesByAmountResponse;
import world.willfrog.alphafrogmicro.domestic.idl.RandomListedEtfsRequest;
import world.willfrog.alphafrogmicro.domestic.idl.RandomListedEtfsResponse;
import world.willfrog.alphafrogmicro.domestic.idl.RandomListedStocksRequest;
import world.willfrog.alphafrogmicro.domestic.idl.RandomListedStocksResponse;
import world.willfrog.alphafrogmicro.domestic.idl.RandomSwL3IndustriesRequest;
import world.willfrog.alphafrogmicro.domestic.idl.RandomSwL3IndustriesResponse;

import java.util.List;

/**
 * 把五个抽样方法注册到现有 domestic-fetch 的 Dubbo 服务上。
 * 参数校验和抽样仍由 DomesticDebugQueryService 完成。
 */
@Service
@DubboService
public class DomesticDebugSampleServiceImpl extends DomesticDebugSampleServiceImplBase {

    private final DomesticDebugQueryService queryService;

    public DomesticDebugSampleServiceImpl(DomesticDebugQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public RandomIndexConstituentsResponse randomIndexConstituents(RandomIndexConstituentsRequest request) {
        try {
            List<DebugAssetNameResponse> items = queryService.randomIndexConstituentStocks(
                    request.getTsCode(), request.getYear(), request.getCount());
            RandomIndexConstituentsResponse.Builder builder = RandomIndexConstituentsResponse.newBuilder()
                    .setStatus(ok());
            for (DebugAssetNameResponse item : items) {
                builder.addItems(toName(item));
            }
            return builder.build();
        } catch (ResponseStatusException rejected) {
            return RandomIndexConstituentsResponse.newBuilder().setStatus(rejected(rejected)).build();
        } catch (RuntimeException failure) {
            return RandomIndexConstituentsResponse.newBuilder().setStatus(gateway()).build();
        }
    }

    @Override
    public RandomSwL3IndustriesResponse randomSwL3Industries(RandomSwL3IndustriesRequest request) {
        try {
            return RandomSwL3IndustriesResponse.newBuilder()
                    .setStatus(ok())
                    .addAllNames(queryService.randomSwL3IndustryNames(request.getCount()))
                    .build();
        } catch (ResponseStatusException rejected) {
            return RandomSwL3IndustriesResponse.newBuilder().setStatus(rejected(rejected)).build();
        } catch (RuntimeException failure) {
            return RandomSwL3IndustriesResponse.newBuilder().setStatus(gateway()).build();
        }
    }

    @Override
    public RandomIndexNamesByAmountResponse randomIndexNamesByAmount(RandomIndexNamesByAmountRequest request) {
        try {
            return RandomIndexNamesByAmountResponse.newBuilder()
                    .setStatus(ok())
                    .setSample(toSample(queryService.randomIndexNamesByCoverage(
                            request.getStartYear(),
                            request.getEndYear(),
                            request.hasMinAvgAmount() ? request.getMinAvgAmount() : null,
                            request.getCnyOnly(),
                            request.getCandidateCount(),
                            request.getMaxAttempts(),
                            request.getCount())))
                    .build();
        } catch (ResponseStatusException rejected) {
            return RandomIndexNamesByAmountResponse.newBuilder().setStatus(rejected(rejected)).build();
        } catch (RuntimeException failure) {
            return RandomIndexNamesByAmountResponse.newBuilder().setStatus(gateway()).build();
        }
    }

    @Override
    public RandomListedStocksResponse randomListedStocks(RandomListedStocksRequest request) {
        try {
            return RandomListedStocksResponse.newBuilder()
                    .setStatus(ok())
                    .setSample(toSample(queryService.randomListedStocks(
                            request.getStartYear(),
                            request.getEndYear(),
                            request.hasMinAvgAmount() ? request.getMinAvgAmount() : null,
                            request.getCandidateCount(),
                            request.getMaxAttempts(),
                            request.getCount())))
                    .build();
        } catch (ResponseStatusException rejected) {
            return RandomListedStocksResponse.newBuilder().setStatus(rejected(rejected)).build();
        } catch (RuntimeException failure) {
            return RandomListedStocksResponse.newBuilder().setStatus(gateway()).build();
        }
    }

    @Override
    public RandomListedEtfsResponse randomListedEtfs(RandomListedEtfsRequest request) {
        try {
            return RandomListedEtfsResponse.newBuilder()
                    .setStatus(ok())
                    .setSample(toSample(queryService.randomListedEtfs(
                            request.getStartYear(),
                            request.getEndYear(),
                            request.hasMinAvgAmount() ? request.getMinAvgAmount() : null,
                            request.getCandidateCount(),
                            request.getMaxAttempts(),
                            request.getCount())))
                    .build();
        } catch (ResponseStatusException rejected) {
            return RandomListedEtfsResponse.newBuilder().setStatus(rejected(rejected)).build();
        } catch (RuntimeException failure) {
            return RandomListedEtfsResponse.newBuilder().setStatus(gateway()).build();
        }
    }

    private static DebugAssetName toName(DebugAssetNameResponse item) {
        DebugAssetName.Builder builder = DebugAssetName.newBuilder()
                .setTsCode(item.tsCode() == null ? "" : item.tsCode())
                .setName(item.name() == null ? "" : item.name());
        if (item.fullName() != null) {
            builder.setFullName(item.fullName()).setHasFullName(true);
        }
        if (item.dailyCount() != null) {
            builder.setDailyCount(item.dailyCount()).setHasDailyCount(true);
        }
        if (item.averageAmount() != null) {
            builder.setAverageAmount(item.averageAmount()).setHasAverageAmount(true);
        }
        return builder.build();
    }

    private static DebugAssetSample toSample(DebugAssetSampleResponse sample) {
        DebugAssetSample.Builder builder = DebugAssetSample.newBuilder()
                .setStatus(sample.status() == null ? "" : sample.status())
                .setRequestedCount(sample.requestedCount())
                .setReturnedCount(sample.returnedCount())
                .setCandidateCount(sample.candidateCount())
                .setAttempts(sample.attempts())
                .setMaxAttempts(sample.maxAttempts())
                .setStartDate(sample.startDate() == null ? "" : sample.startDate())
                .setEndDate(sample.endDate() == null ? "" : sample.endDate())
                .setIdealDailyCount(sample.idealDailyCount())
                .setRequiredDailyCount(sample.requiredDailyCount());
        for (DebugAssetNameResponse item : sample.items()) {
            builder.addItems(toName(item));
        }
        return builder.build();
    }

    private static DebugSampleStatus ok() {
        return DebugSampleStatus.newBuilder().setHttpStatus(HttpStatus.OK.value()).build();
    }

    private static DebugSampleStatus rejected(ResponseStatusException exception) {
        String reason = exception.getReason() == null ? "" : exception.getReason();
        return DebugSampleStatus.newBuilder()
                .setHttpStatus(exception.getStatusCode().value())
                .setMessage(reason)
                .build();
    }

    private static DebugSampleStatus gateway() {
        return DebugSampleStatus.newBuilder()
                .setHttpStatus(HttpStatus.BAD_GATEWAY.value())
                .build();
    }
}
