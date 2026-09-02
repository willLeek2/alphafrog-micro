package world.willfrog.alphafrogmicro.frontend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.alphafrogmicro.common.lane.LaneCallBindingContext;
import world.willfrog.alphafrogmicro.frontend.lane.LaneEntryProperties;
import world.willfrog.alphafrogmicro.frontend.lane.LaneRequestContext;
import world.willfrog.alphafrogmicro.frontend.lane.LaneRouteFacts;
import world.willfrog.alphafrogmicro.frontend.lane.LaneRouteFactsSource;

/**
 * 在 JWT 身份建立后决定当前请求是否进入测试流量范围，并在离开请求线程前恢复旧上下文。
 */
public class LaneWebFilter extends OncePerRequestFilter {

    public static final String TRAFFIC_SCOPE_HEADER = "X-AlphaFrog-Traffic-Scope-Id";
    public static final String DEPLOYMENT_HEADER = "X-AlphaFrog-Deployment-Id";
    public static final String DEPLOYMENT_GENERATION_HEADER = "X-AlphaFrog-Deployment-Generation-Id";
    public static final String LANE_TAG_HEADER = "X-AlphaFrog-Lane-Tag";

    private final LaneEntryProperties properties;
    private final LaneRouteFactsSource routeFactsSource;
    private final Set<String> strippedHeaders;
    private final AtomicBoolean invalidConfigurationReported = new AtomicBoolean();

    public LaneWebFilter(LaneEntryProperties properties, LaneRouteFactsSource routeFactsSource) {
        this.properties = properties;
        this.routeFactsSource = routeFactsSource;
        properties.validateStaticConfiguration();
        this.strippedHeaders = lowercase(Set.of(
                properties.getPassphraseHeader(),
                TRAFFIC_SCOPE_HEADER,
                DEPLOYMENT_HEADER,
                DEPLOYMENT_GENERATION_HEADER,
                LANE_TAG_HEADER,
                LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String suppliedPassphrase = request.getHeader(properties.getPassphraseHeader());
        HttpServletRequest sanitized = new StrippedHeaderRequest(request, strippedHeaders);
        String previousScope = LaneContext.trafficScopeId();
        LaneRouteFacts previousFacts = LaneRequestContext.current();
        LaneCallBindingContext.PinnedBinding previousBinding = LaneCallBindingContext.current();
        String previousMdc = MDC.get(LaneContext.MDC_LANE_TAG);

        LaneContext.clear();
        LaneRequestContext.clear();
        LaneCallBindingContext.clear();
        MDC.remove(LaneContext.MDC_LANE_TAG);
        try {
            if (requiresTaggedRoute(request, suppliedPassphrase)) {
                Optional<LaneRouteFacts> selected = routeFactsSource.current(
                        properties.getTrafficScopeId(), properties.getIdentityServiceName());
                if (selected.isEmpty()) {
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                }
                LaneRouteFacts facts = selected.orElseThrow();
                LaneContext.setTrafficScopeId(facts.trafficScopeId());
                LaneRequestContext.set(facts);
                LaneCallBindingContext.set(properties.resolvedIdentityDubboServiceKey(), facts.callBinding());
                MDC.put(LaneContext.MDC_LANE_TAG, facts.trafficScopeId());
            }
            chain.doFilter(sanitized, response);
        } finally {
            LaneContext.restore(previousScope);
            LaneRequestContext.restore(previousFacts);
            LaneCallBindingContext.restore(previousBinding);
            if (previousMdc == null) {
                MDC.remove(LaneContext.MDC_LANE_TAG);
            } else {
                MDC.put(LaneContext.MDC_LANE_TAG, previousMdc);
            }
        }
    }

    private boolean requiresTaggedRoute(HttpServletRequest request, String suppliedPassphrase) {
        if (!properties.isEnabled()) {
            return false;
        }
        if (!properties.hasUsableEntryConfiguration()) {
            if (invalidConfigurationReported.compareAndSet(false, true)) {
                logger.warn("入口流量范围配置不完整，所有请求都按普通流量处理");
            }
            return false;
        }
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/internal/")) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !properties.getTestUsernames().contains(authentication.getName())
                || !matchesPassphrase(suppliedPassphrase, properties.getPassphrase())) {
            return false;
        }
        return true;
    }

    private static boolean matchesPassphrase(String supplied, String expected) {
        if (supplied == null || expected == null || supplied.isEmpty() || expected.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
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
