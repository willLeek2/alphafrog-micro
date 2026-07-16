package world.willfrog.alphafrogmicro.frontend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import world.willfrog.alphafrogmicro.frontend.service.AdminUserAccessService;
import world.willfrog.alphafrogmicro.frontend.service.debug.DomesticMarketSampleClient;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 管理员随机抽样 facade。
 *
 * <p>{@code /admin/**} 先由 JWT 过滤器认证，本控制器再校验账户类型与状态，
 * 然后通过仅在容器网络开放的 HTTP 接口访问 domestic-fetch。</p>
 */
@RestController
@RequestMapping("/admin/debug/market-samples")
@RequiredArgsConstructor
@Slf4j
public class AdminMarketSampleController {

    private final AdminUserAccessService adminUserAccessService;
    private final DomesticMarketSampleClient marketSampleClient;

    @GetMapping("/index-constituents/random")
    public ResponseEntity<?> randomIndexConstituents(
            Authentication authentication,
            @RequestParam("ts_code") String tsCode,
            @RequestParam("year") int year,
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return adminCall(authentication,
                () -> marketSampleClient.randomIndexConstituents(tsCode, year, count));
    }

    @GetMapping("/sw-l3-industries/random")
    public ResponseEntity<?> randomSwL3Industries(
            Authentication authentication,
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return adminCall(authentication, () -> marketSampleClient.randomSwL3Industries(count));
    }

    @GetMapping("/index-names/random-by-amount")
    public ResponseEntity<?> randomIndexNamesByAmount(
            Authentication authentication,
            @RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam("min_amount") double minAmount,
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return adminCall(authentication,
                () -> marketSampleClient.randomIndexNamesByAmount(
                        startDate, endDate, minAmount, count));
    }

    @GetMapping("/stocks/random")
    public ResponseEntity<?> randomListedStocks(
            Authentication authentication,
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return adminCall(authentication, () -> marketSampleClient.randomListedStocks(count));
    }

    @GetMapping("/etfs/random")
    public ResponseEntity<?> randomListedEtfs(
            Authentication authentication,
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return adminCall(authentication, () -> marketSampleClient.randomListedEtfs(count));
    }

    private ResponseEntity<?> adminCall(Authentication authentication, Supplier<?> action) {
        if (!adminUserAccessService.isActiveAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden"));
        }
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalStateException e) {
            log.error("Random market sample upstream is not configured", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Random market sample service is unavailable"));
        } catch (RestClientException e) {
            log.error("Random market sample upstream request failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Random market sample upstream request failed"));
        }
    }

}
