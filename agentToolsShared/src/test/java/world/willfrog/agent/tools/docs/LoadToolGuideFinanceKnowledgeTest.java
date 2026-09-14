package world.willfrog.agent.tools.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 loadToolGuide 新增 finance_method_knowledge 主题。
 *
 * <p>指南内容由构建插件生成到 {@code target/generated-resources/agent_guides/finance_method_knowledge.md}，
 * 并通过 pom.xml 的 resource 配置加入测试 classpath。</p>
 */
class LoadToolGuideFinanceKnowledgeTest {

    private final LoadToolGuideTool tool = new LoadToolGuideTool(new ObjectMapper());

    @Test
    void shouldLoadFinanceMethodKnowledgeTopic() {
        String result = tool.loadToolGuide("finance_method_knowledge");
        assertTrue(result.contains("\"ok\":true"));
        assertTrue(result.contains("\"topic\":\"finance_method_knowledge\""));
        assertTrue(result.contains("finance.growth.cagr") || result.contains("复合年均增长率"));
    }

    @Test
    void shouldReturnValidTopicErrorForUnknownTopic() {
        String result = tool.loadToolGuide("unknown_topic_xyz");
        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("INVALID_TOPIC"));
    }
}
