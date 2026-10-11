package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动时打出注入 {@code ObjectMapper} 的命名策略类名。
 * Nacos 热配置文件是 camelCase；若注入 mapper 被改成 SNAKE_CASE，POJO 绑定会静默丢键。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InjectedObjectMapperNamingStrategyLogger {

    private final ObjectMapper objectMapper;

    @PostConstruct
    public void logNamingStrategy() {
        PropertyNamingStrategy strategy = objectMapper.getPropertyNamingStrategy();
        log.info("Injected ObjectMapper property naming strategy: {}",
                strategy == null ? "null" : strategy.getClass().getName());
    }
}
