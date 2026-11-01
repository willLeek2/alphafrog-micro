package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@link FinanceMethodResolverCatalog} 读取 resolver-catalog.json、构造稳定顺序目录文本并暴露摘要。
 *
 * <p>测试依赖 Maven generate-resources 阶段生成的 {@code target/generated-resources/finance/method-specs/v1/resolver-catalog.json}。</p>
 */
class FinanceMethodResolverCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();





}
