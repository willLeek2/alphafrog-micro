package world.willfrog.alphafrogmicro.frontend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.alphafrogmicro.frontend.lane.LaneEntryProperties;

/**
 * 在 JWT 身份建立后决定当前请求是否进入某个测试泳道，并在离开请求线程前恢复旧上下文。
 *
 * <p>泳道名有两个来源：部署注入的 {@code alphafrog.lane.entry.traffic-scope-id}（只有泳道自己的
 * frontend 实例会被注入，优先采用，此时不看请求头），以及共用入口的请求头
 * {@code X-AlphaFrog-Lane-Tag}（主 Beta frontend 上没有注入时的兜底）。两者按同一套规则解析：
 * 空白或 {@code main-beta} 不打标；格式不合法只告警一次并按普通流量处理。所有可伪造的入口
 * 标记头都会在进入业务代码前剥除，业务代码只认线程上下文里的泳道名。</p>
 */
public class LaneWebFilter extends OncePerRequestFilter {

    public static final String TRAFFIC_SCOPE_HEADER = "X-AlphaFrog-Traffic-Scope-Id";
    public static final String DEPLOYMENT_HEADER = "X-AlphaFrog-Deployment-Id";
    public static final String DEPLOYMENT_GENERATION_HEADER = "X-AlphaFrog-Deployment-Generation-Id";
    public static final String LANE_TAG_HEADER = "X-AlphaFrog-Lane-Tag";

    private static final String LANE_TAG_PATTERN = "^[a-z0-9]([a-z0-9._-]{0,94}[a-z0-9])?$";

    private final LaneEntryProperties properties;
    private final Set<String> strippedHeaders;
    private final AtomicBoolean invalidLaneTagReported = new AtomicBoolean();

    public LaneWebFilter(LaneEntryProperties properties) {
        this.properties = properties;
        this.strippedHeaders = lowercase(Set.of(
                LANE_TAG_HEADER,
                TRAFFIC_SCOPE_HEADER,
                DEPLOYMENT_HEADER,
                DEPLOYMENT_GENERATION_HEADER,
                LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID));
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String headerLaneTag = request.getHeader(LANE_TAG_HEADER);
        HttpServletRequest sanitized = new StrippedHeaderRequest(request, strippedHeaders);
        String previousScope = LaneContext.trafficScopeId();
        String previousMdc = MDC.get(LaneContext.MDC_LANE_TAG);

        LaneContext.clear();
        MDC.remove(LaneContext.MDC_LANE_TAG);
        try {
            String laneTag = properties.isEnabled() ? resolveLaneTag(headerLaneTag) : null;
            if (requiresTag(request, laneTag)) {
                LaneContext.setTrafficScopeId(laneTag);
                MDC.put(LaneContext.MDC_LANE_TAG, laneTag);
            }
            chain.doFilter(sanitized, response);
        } finally {
            LaneContext.restore(previousScope);
            if (previousMdc == null) {
                MDC.remove(LaneContext.MDC_LANE_TAG);
            } else {
                MDC.put(LaneContext.MDC_LANE_TAG, previousMdc);
            }
        }
    }

    @Override
    protected void doFilterNestedErrorDispatch(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new StrippedHeaderRequest(request, strippedHeaders), response);
    }

    private boolean requiresTag(HttpServletRequest request, String laneTag) {
        if (!properties.isEnabled()) {
            return false;
        }
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/internal/")) {
            return false;
        }
        return laneTag != null;
    }

    /**
     * 泳道名解析：部署注入值优先；没有注入时使用共用入口请求头。解析规则与
     * {@code AgentRunEventService.normalizeLaneTag} 一致，但格式不合法时不抛异常，
     * 只告警一次并按普通流量处理。
     */
    private String resolveLaneTag(String headerLaneTag) {
        String injected = properties.getTrafficScopeId();
        if (injected != null && !injected.isBlank()) {
            return normalizeLaneTag(injected);
        }
        return normalizeLaneTag(headerLaneTag);
    }

    private String normalizeLaneTag(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String normalized = candidate.trim();
        if (LaneContext.MAIN_BETA_TRAFFIC_SCOPE_ID.equals(normalized)) {
            return null;
        }
        if (!normalized.matches(LANE_TAG_PATTERN)) {
            if (invalidLaneTagReported.compareAndSet(false, true)) {
                logger.warn("入口泳道请求头格式不合法，按普通流量处理");
            }
            return null;
        }
        return normalized;
    }

    private static Set<String> lowercase(Set<String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            result.add(name.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private static final class StrippedHeaderRequest extends HttpServletRequestWrapper {

        private final Set<String> strippedHeaders;

        private StrippedHeaderRequest(HttpServletRequest request, Set<String> strippedHeaders) {
            super(request);
            this.strippedHeaders = strippedHeaders;
        }

        @Override
        public String getHeader(String name) {
            return isStripped(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isStripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Enumeration<String> names = super.getHeaderNames();
            if (names == null) {
                return Collections.emptyEnumeration();
            }
            return Collections.enumeration(Collections.list(names).stream()
                    .filter(name -> !isStripped(name))
                    .toList());
        }

        private boolean isStripped(String name) {
            return name != null && strippedHeaders.contains(name.toLowerCase(Locale.ROOT));
        }
    }
}
