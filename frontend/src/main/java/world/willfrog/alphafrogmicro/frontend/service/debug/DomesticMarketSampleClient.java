package world.willfrog.alphafrogmicro.frontend.service.debug;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * frontend 到 domestic-fetch 随机抽样接口的内部客户端。
 *
 * <p>外部调用者只提交正常登录得到的 JWT。管理员校验由 frontend 完成，
 * domestic-fetch 端口仅在容器网络内开放。</p>
 */
@Service
public class DomesticMarketSampleClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> ASSET_LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Map<String, Object>> ASSET_SAMPLE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<String>> STRING_LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestTemplate restTemplate;
    private final String baseUrl;

    @Autowired
    public DomesticMarketSampleClient(
            @Value("${alphafrog.domestic-fetch.debug-base-url:http://domestic-fetch-service:18082}")
            String baseUrl) {
        this(buildRestTemplate(), baseUrl);
    }

    DomesticMarketSampleClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    public List<Map<String, Object>> randomIndexConstituents(
            String tsCode, int year, int count) {
        URI uri = uri("/debug/index-constituents/random")
                .queryParam("ts_code", tsCode)
                .queryParam("year", year)
                .queryParam("count", count)
                .build()
                .encode()
                .toUri();
        return exchange(uri, ASSET_LIST_TYPE);
    }

    public List<String> randomSwL3Industries(int count) {
        URI uri = uri("/debug/sw-l3-industries/random")
                .queryParam("count", count)
                .build()
                .encode()
                .toUri();
        return exchange(uri, STRING_LIST_TYPE);
    }

    public Map<String, Object> randomIndexNamesByAmount(
            int startYear, int endYear, Double minAverageAmount, int count) {
        UriComponentsBuilder builder = uri("/debug/index-names/random-by-amount")
                .queryParam("start_year", startYear)
                .queryParam("end_year", endYear)
                .queryParam("count", count);
        addAverageAmount(builder, minAverageAmount);
        return exchange(builder.build().encode().toUri(), ASSET_SAMPLE_TYPE);
    }

    public Map<String, Object> randomListedStocks(
            int startYear, int endYear, Double minAverageAmount, int count) {
        UriComponentsBuilder builder = uri("/debug/stocks/random")
                .queryParam("start_year", startYear)
                .queryParam("end_year", endYear)
                .queryParam("count", count);
        addAverageAmount(builder, minAverageAmount);
        return exchange(builder.build().encode().toUri(), ASSET_SAMPLE_TYPE);
    }

    public Map<String, Object> randomListedEtfs(
            int startYear, int endYear, Double minAverageAmount, int count) {
        UriComponentsBuilder builder = uri("/debug/etfs/random")
                .queryParam("start_year", startYear)
                .queryParam("end_year", endYear)
                .queryParam("count", count);
        addAverageAmount(builder, minAverageAmount);
        return exchange(builder.build().encode().toUri(), ASSET_SAMPLE_TYPE);
    }

    private void addAverageAmount(UriComponentsBuilder builder, Double minAverageAmount) {
        if (minAverageAmount != null) {
            builder.queryParam("min_avg_amount", minAverageAmount);
        }
    }

    private UriComponentsBuilder uri(String path) {
        return UriComponentsBuilder.fromUriString(baseUrl).path(path);
    }

    private <T> T exchange(URI uri, ParameterizedTypeReference<T> type) {
        ResponseEntity<T> response = restTemplate.exchange(
                uri, HttpMethod.GET, HttpEntity.EMPTY, type);
        T body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("domestic-fetch debug endpoint returned an empty body");
        }
        return body;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        return new RestTemplate(factory);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("domestic-fetch debug base URL is blank");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
