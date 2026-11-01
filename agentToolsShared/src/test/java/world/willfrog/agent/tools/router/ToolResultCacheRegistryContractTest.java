package world.willfrog.agent.tools.router;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.tools.registry.AgentToolRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolResultCacheService 缓存模式与 AgentToolRegistry cacheFamily 元数据的一致性契约测试。
 *
 * <p>resolveMode 为 private，故通过 ReflectionTestUtils 强制调用；仅做只读断言。</p>
 */
class ToolResultCacheRegistryContractTest {

    private final ToolResultCacheService service = new ToolResultCacheService(
            null, null, null, null, null);


    private String expectedMode(AgentToolRegistry.CacheFamily family) {
        return switch (family) {
            case SEARCH, INFO -> "REDIS";
            case DATASET -> "DATASET_REGISTRY";
            case NONE -> "NONE";
        };
    }
}
