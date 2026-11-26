package world.willfrog.alphafrogmicro.frontend.service.debug;

import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import world.willfrog.alphafrogmicro.domestic.idl.DebugAssetName;
import world.willfrog.alphafrogmicro.domestic.idl.DebugAssetSample;
import world.willfrog.alphafrogmicro.domestic.idl.DebugSampleStatus;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticDebugSampleService;
import world.willfrog.alphafrogmicro.domestic.idl.RandomIndexConstituentsRequest;
import world.willfrog.alphafrogmicro.domestic.idl.RandomIndexNamesByAmountRequest;
import world.willfrog.alphafrogmicro.domestic.idl.RandomListedEtfsRequest;
import world.willfrog.alphafrogmicro.domestic.idl.RandomListedStocksRequest;
import world.willfrog.alphafrogmicro.domestic.idl.RandomSwL3IndustriesRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * frontend 到 domestic-fetch 抽样接口的客户端。
 * 调用走注册中心里的 Dubbo 服务，主机和端口由注册中心给出。
 * 管理员校验留在 frontend。
 */
@Service
public class DomesticMarketSampleClient {

    @DubboReference(timeout = 35000)
    private DomesticDebugSampleService sampleService;

    public List<Map<String, Object>> randomIndexConstituents(String tsCode, int year, int count) {
        try {
            var response = service().randomIndexConstituents(RandomIndexConstituentsRequest.newBuilder()
                    .setTsCode(tsCode == null ? "" : tsCode)
                    .setYear(year)
                    .setCount(count)
                    .build());
            ensureOk(response.getStatus());
            List<Map<String, Object>> items = new ArrayList<>();
            for (DebugAssetName item : response.getItemsList()) {
                items.add(toName(item));
            }
            return items;
        } catch (RpcException failure) {
            throw gateway();
        }
    }

    public List<String> randomSwL3Industries(int count) {
        try {
            var response = service().randomSwL3Industries(RandomSwL3IndustriesRequest.newBuilder()
                    .setCount(count)
                    .build());
            ensureOk(response.getStatus());
            return List.copyOf(response.getNamesList());
        } catch (RpcException failure) {
            throw gateway();
        }
    }

    public Map<String, Object> randomIndexNamesByAmount(
            int startYear,
            int endYear,
            Double minAverageAmount,
            boolean cnyOnly,
            int candidateCount,
            int maxAttempts,
            int count) {
        try {
            RandomIndexNamesByAmountRequest.Builder request = RandomIndexNamesByAmountRequest.newBuilder()
                    .setStartYear(startYear)
                    .setEndYear(endYear)
                    .setCnyOnly(cnyOnly)
                    .setCandidateCount(candidateCount)
                    .setMaxAttempts(maxAttempts)
                    .setCount(count);
            if (minAverageAmount != null) {
                request.setMinAvgAmount(minAverageAmount);
            }
            var response = service().randomIndexNamesByAmount(request.build());
            ensureOk(response.getStatus());
            return toSample(response.getSample());
        } catch (RpcException failure) {
            throw gateway();
        }
    }

    public Map<String, Object> randomListedStocks(
            int startYear,
            int endYear,
            Double minAverageAmount,
            int candidateCount,
            int maxAttempts,
            int count) {
        try {
            RandomListedStocksRequest.Builder request = RandomListedStocksRequest.newBuilder()
                    .setStartYear(startYear)
                    .setEndYear(endYear)
                    .setCandidateCount(candidateCount)
                    .setMaxAttempts(maxAttempts)
                    .setCount(count);
            if (minAverageAmount != null) {
                request.setMinAvgAmount(minAverageAmount);
            }
            var response = service().randomListedStocks(request.build());
            ensureOk(response.getStatus());
            return toSample(response.getSample());
        } catch (RpcException failure) {
            throw gateway();
        }
    }

    public Map<String, Object> randomListedEtfs(
            int startYear,
            int endYear,
            Double minAverageAmount,
            int candidateCount,
            int maxAttempts,
            int count) {
        try {
            RandomListedEtfsRequest.Builder request = RandomListedEtfsRequest.newBuilder()
                    .setStartYear(startYear)
                    .setEndYear(endYear)
                    .setCandidateCount(candidateCount)
                    .setMaxAttempts(maxAttempts)
                    .setCount(count);
            if (minAverageAmount != null) {
                request.setMinAvgAmount(minAverageAmount);
            }
            var response = service().randomListedEtfs(request.build());
            ensureOk(response.getStatus());
            return toSample(response.getSample());
        } catch (RpcException failure) {
            throw gateway();
        }
    }

    private DomesticDebugSampleService service() {
        if (sampleService == null) {
            throw new IllegalStateException("Random market sample upstream is not configured");
        }
        return sampleService;
    }

    private static void ensureOk(DebugSampleStatus status) {
        int code = status.getHttpStatus();
        if (code == HttpStatus.OK.value()) {
            return;
        }
        HttpStatus resolved = HttpStatus.resolve(code);
        if (resolved == null) {
            resolved = HttpStatus.BAD_GATEWAY;
        }
        if (resolved.is4xxClientError()) {
            throw HttpClientErrorException.create(
                    resolved, "sample", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        }
        throw HttpServerErrorException.create(
                resolved, "sample", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    private static HttpStatusCodeException gateway() {
        return HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "sample", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    private static Map<String, Object> toSample(DebugAssetSample sample) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", sample.getStatus());
        body.put("requested_count", sample.getRequestedCount());
        body.put("returned_count", sample.getReturnedCount());
        body.put("candidate_count", sample.getCandidateCount());
        body.put("attempts", sample.getAttempts());
        body.put("max_attempts", sample.getMaxAttempts());
        body.put("start_date", sample.getStartDate());
        body.put("end_date", sample.getEndDate());
        body.put("ideal_daily_count", sample.getIdealDailyCount());
        body.put("required_daily_count", sample.getRequiredDailyCount());
        List<Map<String, Object>> items = new ArrayList<>();
        for (DebugAssetName item : sample.getItemsList()) {
            items.add(toName(item));
        }
        body.put("items", items);
        return body;
    }

    private static Map<String, Object> toName(DebugAssetName item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tsCode", item.getTsCode());
        body.put("name", item.getName());
        if (item.getHasFullName()) {
            body.put("full_name", item.getFullName());
        }
        if (item.getHasDailyCount()) {
            body.put("daily_count", item.getDailyCount());
        }
        if (item.getHasAverageAmount()) {
            body.put("average_amount", item.getAverageAmount());
        }
        return body;
    }
}
