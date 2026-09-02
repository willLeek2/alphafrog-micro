package world.willfrog.alphafrogmicro.frontend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.frontend.filter.FetchAccessFilter;
import world.willfrog.alphafrogmicro.frontend.filter.JwtAuthFilter;
import world.willfrog.alphafrogmicro.frontend.filter.LaneWebFilter;
import world.willfrog.alphafrogmicro.frontend.lane.ControllerLaneRouteFactsClient;
import world.willfrog.alphafrogmicro.frontend.lane.DirectLaneRouteFactsSource;
import world.willfrog.alphafrogmicro.frontend.lane.FrontendDeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.frontend.lane.LaneEntryProperties;
import world.willfrog.alphafrogmicro.frontend.lane.LaneRouteFactsSource;

/** 把入口过滤器只安装到 Spring Security 链，禁止 Servlet 容器重复注册。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LaneEntryProperties.class)
public class LaneEntryConfiguration {

    @Bean
    LaneRouteFactsSource laneRouteFactsSource(LaneEntryProperties properties, ObjectMapper objectMapper) {
        return new DirectLaneRouteFactsSource(new ControllerLaneRouteFactsClient(properties, objectMapper));
    }

    @Bean
    LaneWebFilter laneWebFilter(LaneEntryProperties properties, LaneRouteFactsSource routeFactsSource) {
        return new LaneWebFilter(properties, routeFactsSource);
    }

    @Bean
    DeploymentIdentityProvider frontendDeploymentIdentityProvider(LaneEntryProperties properties) {
        return new FrontendDeploymentIdentityProvider(properties);
    }

    @Bean
    FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    FilterRegistrationBean<LaneWebFilter> laneWebFilterRegistration(LaneWebFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    FilterRegistrationBean<FetchAccessFilter> fetchAccessFilterRegistration(FetchAccessFilter filter) {
        return disabledRegistration(filter);
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabledRegistration(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
