package world.willfrog.alphafrogmicro.common.gray;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/** 自动提供默认关闭、可由服务覆盖的灰度判断入口。 */
@AutoConfiguration
@ConditionalOnClass({ObjectMapper.class, GrayDecider.class})
public class GrayAutoConfiguration {

    public static final String DEFAULT_RULES_FILE = "/app/config-dynamic/gray-rules.local.json";
    public static final long DEFAULT_REFRESH_INTERVAL_MILLIS = 5_000L;

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "alphafrog.gray", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public GrayRuleStore grayRuleStore(
            ObjectProvider<ObjectMapper> objectMapperProvider,
            @Value("${alphafrog.gray.rules-file:" + DEFAULT_RULES_FILE + "}") String rulesFile,
            @Value("${alphafrog.gray.refresh-interval-ms:" + DEFAULT_REFRESH_INTERVAL_MILLIS + "}")
            long refreshIntervalMillis,
            @Value("${spring.application.name:unknown}") String serviceName,
            @Value("${spring.application.instance-id:${HOSTNAME:unknown}}") String instanceId) {
        if (rulesFile == null || rulesFile.isBlank()) {
            throw new IllegalArgumentException("alphafrog.gray.rules-file must not be blank");
        }
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new GrayRuleStore(
                objectMapper,
                Path.of(rulesFile),
                refreshIntervalMillis,
                serviceName,
                instanceId);
    }

    @Bean
    @ConditionalOnMissingBean(GrayDecider.class)
    public GrayDecider grayDecider(
            ObjectProvider<GrayRuleStore> storeProvider,
            @Value("${alphafrog.gray.enabled:false}") String enabled) {
        boolean dynamicEnabled = enabled != null && "true".equalsIgnoreCase(enabled.trim());
        GrayRuleStore store = dynamicEnabled ? storeProvider.getIfAvailable() : null;
        return store == null ? GrayDecider.disabled() : new GrayDecider(store);
    }
}
