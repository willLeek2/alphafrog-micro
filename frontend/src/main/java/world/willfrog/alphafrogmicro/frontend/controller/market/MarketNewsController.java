package world.willfrog.alphafrogmicro.frontend.controller.market;

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
import world.willfrog.alphafrogmicro.frontend.model.market.MarketNewsResponse;
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
    public ResponseWrapper<MarketNewsResponse> today(Authentication authentication,
                                                     @RequestParam(value = "limit", required = false) Integer limit,
                                                     @RequestParam(value = "provider", required = false) String provider,
                                                     @RequestParam(value = "language", required = false) String language,
                                                     @RequestParam(value = "startPublishedDate", required = false) String startPublishedDate,
                                                     @RequestParam(value = "endPublishedDate", required = false) String endPublishedDate) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        User user = authService.getUserByUsername(authentication.getName());
        if (!authService.isUserActive(user)) {
            return ResponseWrapper.error(ResponseCode.FORBIDDEN, "账号已被禁用");
        }
        try {
            int safeLimit = limit == null ? 0 : Math.max(0, Math.min(limit, 20));
            var resp = agentDubboService.getTodayMarketNews(
                    GetTodayMarketNewsRequest.newBuilder()
                            .setUserId(userId)
                            .setLimit(safeLimit)
                            .setProvider(blankToEmpty(provider))
                            .setLanguage(blankToEmpty(language))
                            .setStartPublishedDate(blankToEmpty(startPublishedDate))
                            .setEndPublishedDate(blankToEmpty(endPublishedDate))
                            .build()
            );
            List<MarketNewsItemResponse> items = new ArrayList<>();
            resp.getDataList().forEach(item -> items.add(new MarketNewsItemResponse(
                    emptyToNull(item.getId()),
                    emptyToNull(item.getTimestamp()),
                    item.getTitle(),
                    emptyToNull(item.getSource()),
                    emptyToNull(item.getCategory()),
                    emptyToNull(item.getUrl())
            )));
            MarketNewsResponse data = new MarketNewsResponse(
                    items,
                    emptyToNull(resp.getUpdatedAt()),
                    emptyToNull(resp.getProvider())
            );
            return ResponseWrapper.success(data);
        } catch (RpcException e) {
            log.error("查询市场新闻失败: {}", e.getMessage());
            return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, "查询市场新闻失败");
        } catch (Exception e) {
            log.error("查询市场新闻失败", e);
            return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, "查询市场新闻失败");
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

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
