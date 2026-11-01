package world.willfrog.alphafrogmicro.frontend.controller.agent;

import org.apache.dubbo.config.annotation.DubboReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.util.ClassUtils;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentCreditGateway;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 防回归测试：确认 AgentDubboService 的 Dubbo 路由没有 `group="*"` 混用，
 * 且 agent HTTP controller 只消费 langchain provider。
 */
class AgentDubboServiceRoutingRegressionTest {

    private static final String CONTROLLER_BASE_PACKAGE = "world.willfrog.alphafrogmicro.frontend.controller";
    private static final String SERVICE_BASE_PACKAGE = "world.willfrog.alphafrogmicro.frontend.service";


}
