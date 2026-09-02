package world.willfrog.alphafrogmicro.common.lane;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/** 自动提供默认关闭的服务间调用路由入口。 */
@AutoConfiguration
@ConditionalOnClass(LaneCallRouter.class)
public class LaneAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "alphafrog.lane.routing", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(LaneRoutePointer.class)
    public LaneRoutePointer laneRoutePointer(
            ObjectProvider<ObjectMapper> objectMapperProvider,
            @Value("${alphafrog.lane.controller-state-file:"
                    + ControllerStateLaneRoutePointer.DEFAULT_STATE_FILE + "}")
            String stateFile) {
        if (stateFile == null || stateFile.isBlank()) {
            throw new IllegalArgumentException("alphafrog.lane.controller-state-file must not be blank");
        }
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new ControllerStateLaneRoutePointer(objectMapper, Path.of(stateFile));
    }

    @Bean
    @ConditionalOnMissingBean(LaneCallRouter.class)
    public LaneCallRouter laneCallRouter(
            ObjectProvider<LaneRoutePointer> pointerProvider,
            @Value("${alphafrog.lane.routing.enabled:false}") String enabled) {
        boolean routingEnabled = enabled != null && "true".equalsIgnoreCase(enabled.trim());
        LaneRoutePointer pointer = routingEnabled ? pointerProvider.getIfAvailable() : null;
        LaneCallRouter router = pointer == null ? LaneCallRouter.disabled() : new LaneCallRouter(pointer);
        LaneRoutingSupport.install(router, routingEnabled && pointer != null);
        return router;
    }
}
