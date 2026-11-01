package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.AnnotationMetadata;
import world.willfrog.agent.platform.mapper.AgentRunDagNodeMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证 @ConditionalOnProperty(matchIfMissing=false) 的语义：
 * - 默认（未设置属性）→ bean 不创建
 * - 显式 true → bean 创建
 * - 显式 false → bean 不创建
 */
class CancelReconcilerConditionalTest {

    @Configuration
    static class TestConfig {
        @Bean
        AgentRunDagNodeMapper dagNodeMapper() {
            return mock(AgentRunDagNodeMapper.class);
        }

        @Bean
        CancelWorker cancelWorker(AgentRunDagNodeMapper mapper) {
            return new CancelWorker(mapper, null);
        }
    }




}
