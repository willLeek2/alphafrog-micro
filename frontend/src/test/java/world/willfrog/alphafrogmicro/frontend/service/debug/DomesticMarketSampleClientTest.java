package world.willfrog.alphafrogmicro.frontend.service.debug;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DomesticMarketSampleClientTest {

    @Test
    void forwardsRequestWithInternalToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        DomesticMarketSampleClient client = new DomesticMarketSampleClient(
                restTemplate, "http://domestic-fetch-service:18082", "internal-secret");

        server.expect(requestTo(
                        "http://domestic-fetch-service:18082/debug/index-names/random-by-amount"
                                + "?start_date=20250101&end_date=20251231&min_amount=100000.0&count=2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Admin-Token", "internal-secret"))
                .andRespond(withSuccess(
                        "[{\"tsCode\":\"000300.SH\",\"name\":\"沪深300\"}]",
                        MediaType.APPLICATION_JSON));

        List<Map<String, Object>> result = client.randomIndexNamesByAmount(
                "20250101", "20251231", 100000, 2);

        assertEquals("000300.SH", result.get(0).get("tsCode"));
        assertEquals("沪深300", result.get(0).get("name"));
        server.verify();
    }

    @Test
    void missingInternalTokenFailsClosedBeforeHttpCall() {
        DomesticMarketSampleClient client = new DomesticMarketSampleClient(
                new RestTemplate(), "http://domestic-fetch-service:18082", "");

        assertThrows(IllegalStateException.class, () -> client.randomSwL3Industries(1));
    }

    @Test
    void forwardsRandomStockAndEtfRequestsWithInternalToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        DomesticMarketSampleClient client = new DomesticMarketSampleClient(
                restTemplate, "http://domestic-fetch-service:18082", "internal-secret");
        server.expect(requestTo("http://domestic-fetch-service:18082/debug/stocks/random?count=2"))
                .andExpect(header("X-Admin-Token", "internal-secret"))
                .andRespond(withSuccess(
                        "[{\"tsCode\":\"600519.SH\",\"name\":\"贵州茅台\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://domestic-fetch-service:18082/debug/etfs/random?count=2"))
                .andExpect(header("X-Admin-Token", "internal-secret"))
                .andRespond(withSuccess(
                        "[{\"tsCode\":\"510300.SH\",\"name\":\"沪深300ETF\"}]",
                        MediaType.APPLICATION_JSON));

        assertEquals("600519.SH", client.randomListedStocks(2).get(0).get("tsCode"));
        assertEquals("510300.SH", client.randomListedEtfs(2).get(0).get("tsCode"));
        server.verify();
    }
}
