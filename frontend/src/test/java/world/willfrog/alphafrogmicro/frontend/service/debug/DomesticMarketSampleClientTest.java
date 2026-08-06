package world.willfrog.alphafrogmicro.frontend.service.debug;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DomesticMarketSampleClientTest {

    @Test
    void forwardsRequestInsideContainerNetwork() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        DomesticMarketSampleClient client = new DomesticMarketSampleClient(
                restTemplate, "http://domestic-fetch-service:18082");

        server.expect(requestTo(
                        "http://domestic-fetch-service:18082/debug/index-names/random-by-amount"
                                + "?start_year=2021&end_year=2025"
                                + "&cny_only=true"
                                + "&candidate_count=7&max_attempts=3"
                                + "&count=2&min_avg_amount=100000.0"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\":\"complete\",\"candidate_count\":7,"
                                + "\"max_attempts\":3,\"items\":["
                                + "{\"tsCode\":\"000300.SH\",\"name\":\"沪深300\","
                                + "\"full_name\":\"沪深300指数\"}]}",
                        MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.randomIndexNamesByAmount(
                2021, 2025, 100000.0, true, 7, 3, 2);

        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((java.util.List<Map<String, Object>>) result.get("items")).get(0);
        assertEquals("000300.SH", item.get("tsCode"));
        assertEquals("沪深300", item.get("name"));
        assertEquals("沪深300指数", item.get("full_name"));
        assertEquals(7, result.get("candidate_count"));
        assertEquals(3, result.get("max_attempts"));
        server.verify();
    }

    @Test
    void forwardsRandomStockAndEtfRequestsInsideContainerNetwork() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        DomesticMarketSampleClient client = new DomesticMarketSampleClient(
                restTemplate, "http://domestic-fetch-service:18082");
        server.expect(requestTo(
                        "http://domestic-fetch-service:18082/debug/stocks/random"
                                + "?start_year=2021&end_year=2025"
                                + "&candidate_count=7&max_attempts=3&count=2"))
                .andRespond(withSuccess(
                        "{\"status\":\"complete\",\"items\":["
                                + "{\"tsCode\":\"600519.SH\",\"name\":\"贵州茅台\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "http://domestic-fetch-service:18082/debug/etfs/random"
                                + "?start_year=2021&end_year=2025"
                                + "&candidate_count=7&max_attempts=3"
                                + "&count=2&min_avg_amount=50000.0"))
                .andRespond(withSuccess(
                        "{\"status\":\"complete\",\"items\":["
                                + "{\"tsCode\":\"510300.SH\",\"name\":\"沪深300ETF\"}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals("complete",
                client.randomListedStocks(2021, 2025, null, 7, 3, 2).get("status"));
        assertEquals("complete",
                client.randomListedEtfs(2021, 2025, 50000.0, 7, 3, 2).get("status"));
        server.verify();
    }

    @Test
    void exposesUnprocessableEntityFromDomesticFetch() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        DomesticMarketSampleClient client = new DomesticMarketSampleClient(
                restTemplate, "http://domestic-fetch-service:18082");
        server.expect(requestTo(
                        "http://domestic-fetch-service:18082/debug/stocks/random"
                                + "?start_year=2021&end_year=2025"
                                + "&candidate_count=7&max_attempts=3&count=1"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"no eligible asset\"}"));

        HttpClientErrorException exception = assertThrows(
                HttpClientErrorException.class,
                () -> client.randomListedStocks(2021, 2025, null, 7, 3, 1));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatusCode());
        server.verify();
    }
}
