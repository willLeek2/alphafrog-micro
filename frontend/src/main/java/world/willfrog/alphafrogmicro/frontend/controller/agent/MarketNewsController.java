package world.willfrog.alphafrogmicro.frontend.controller.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.GetTodayMarketNewsRequest;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.market.MarketNewsItemResponse;
import world.willfrog.alphafrogmicro.frontend.model.market.TodayMarketNewsResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/market/news")
@RequiredArgsConstructor
@Slf4j
public class MarketNewsController {

    @DubboReference
    private AgentDubboService agentDubboService;

    private final AuthService authService;

    @GetMapping("/today")
    public ResponseWrapper<TodayMarketNewsResponse> today(Authentication authentication,
                                                          @RequestParam(value = "limit", required = false) Integer limit,
                                                          @RequestParam(value = "provider", required = false) String provider,
                                                          @RequestParam(value = "market", required = false) String market,
                                                          @RequestParam(value = "language", required = false) String language,
                                                          @RequestParam(value = "startPublishedAt", required = false) String startPublishedAt,
                                                          @RequestParam(value = "endPublishedAt", required = false) String endPublishedAt) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            var rpcResp = agentDubboService.getTodayMarketNews(
                    GetTodayMarketNewsRequest.newBuilder()
                            .setUserId(userId)
                            .setLimit(limit == null ? 0 : limit)
                            .setProvider(nvl(provider))
                            .setMarket(nvl(market))
                            .setLanguage(nvl(language))
                            .setStartPublishedAt(nvl(startPublishedAt))
                            .setEndPublishedAt(nvl(endPublishedAt))
                            .build()
            );
            List<MarketNewsItemResponse> items = new ArrayList<>();
            for (var item : rpcResp.getDataList()) {
                items.add(new MarketNewsItemResponse(
                        item.getId(),
                        item.getTimestamp(),
                        item.getTitle(),
                        item.getSource(),
                        item.getCategory(),
                        item.getUrl(),
                        item.getSummary(),
                        item.getProvider()
                ));
            }
            return ResponseWrapper.success(new TodayMarketNewsResponse(items, rpcResp.getUpdatedAt(), rpcResp.getProvider()));
        } catch (RpcException e) {
            log.error("查询今日市场新闻失败: {}", e.getMessage());
            return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, "查询今日市场新闻失败");
        } catch (Exception e) {
            log.error("查询今日市场新闻失败", e);
            return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, "查询今日市场新闻失败");
        }
    }

    private String resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        User user = authService.getUserByUsername(authentication.getName());
        if (user == null || user.getUserId() == null) {
            return null;
        }
        return String.valueOf(user.getUserId());
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }
}
