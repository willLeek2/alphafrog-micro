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
import world.willfrog.alphafrogmicro.agent.idl.GetTodayMarketNewsResponse;
import world.willfrog.alphafrogmicro.agent.idl.MarketNewsItemMessage;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.market.MarketNewsItemResponse;
import world.willfrog.alphafrogmicro.frontend.model.market.MarketNewsResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1/market/news")
@RequiredArgsConstructor
@Slf4j
public class MarketNewsController {

    private static final int MAX_LIMIT = 50;

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
        try {
            List<String> languages = parseLanguages(language);
            GetTodayMarketNewsRequest.Builder builder = GetTodayMarketNewsRequest.newBuilder()
                    .setUserId(userId)
                    .setLimit(resolveLimit(limit))
                    .setProvider(nvl(provider))
                    .setStartPublishedDate(nvl(startPublishedDate))
                    .setEndPublishedDate(nvl(endPublishedDate));
            if (!languages.isEmpty()) {
                builder.addAllLanguages(languages);
            }
            GetTodayMarketNewsResponse resp = agentDubboService.getTodayMarketNews(builder.build());
            List<MarketNewsItemResponse> items = new ArrayList<>();
            for (MarketNewsItemMessage item : resp.getDataList()) {
                items.add(new MarketNewsItemResponse(
                        emptyToNull(item.getId()),
                        emptyToNull(item.getTimestamp()),
                        emptyToNull(item.getTitle()),
                        emptyToNull(item.getSource()),
                        emptyToNull(item.getCategory()),
                        emptyToNull(item.getUrl())
                ));
            }
            MarketNewsResponse body = new MarketNewsResponse(
                    items,
                    emptyToNull(resp.getUpdatedAt()),
                    emptyToNull(resp.getProvider())
            );
            return ResponseWrapper.success(body);
        } catch (RpcException e) {
            log.error("查询市场新闻失败: {}", e.getMessage(), e);
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
        String username = authentication.getName();
        User user = authService.getUserByUsername(username);
        if (user == null || user.getUserId() == null) {
            return null;
        }
        return String.valueOf(user.getUserId());
    }

    private List<String> parseLanguages(String language) {
        if (language == null || language.isBlank()) {
            return List.of();
        }
        return Arrays.stream(language.split(","))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 0;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String nvl(String value) {
        return value == null ? "" : value.trim();
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
