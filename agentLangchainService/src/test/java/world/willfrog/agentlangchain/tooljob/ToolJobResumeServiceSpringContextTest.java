package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * 验证 Spring 生产上下文会选择四参数构造器，而不是测试专用的五参数构造器。
 */
class ToolJobResumeServiceSpringContextTest {

    @Test
    void springShouldInstantiateServiceWithoutAStringBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ToolJobAnchorService.class, () -> mock(ToolJobAnchorService.class));
            context.registerBean(ToolJobRedisCache.class, () -> mock(ToolJobRedisCache.class));
            context.registerBean(ToolJobConfig.class, () -> mock(ToolJobConfig.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(ToolJobResumeService.class);
            context.refresh();

            assertNotNull(context.getBean(ToolJobResumeService.class));
        }
    }
}
