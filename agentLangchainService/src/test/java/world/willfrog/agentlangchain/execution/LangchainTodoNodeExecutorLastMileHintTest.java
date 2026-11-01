package world.willfrog.agentlangchain.execution;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3.2 A2: focused tests for {@link LangchainTodoNodeExecutor#maybeInjectLastMileHint(ChatRequest)}.
 * <p>
 * 覆盖口径：
 * <ul>
 *   <li>ThreadLocal 无 hint → 原样返回（不重建 ChatRequest）</li>
 *   <li>ThreadLocal hint 为 null / blank → 原样返回，不消费</li>
 *   <li>ThreadLocal hint 存在 → 作为新的 UserMessage 追加，稳定 System 字节不变</li>
 *   <li>注入完成后 AgentContext 必须被清空，避免下次 tool-loop 再次消费同一份 hint</li>
 *   <li>重建后的 ChatRequest 必须保留 temperature / toolSpecifications 等原始字段（拷贝构造器继承）</li>
 * </ul>
 */
class LangchainTodoNodeExecutorLastMileHintTest {

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }








    private ChatRequest sampleRequest() {
        return ChatRequest.builder()
                .messages(List.of(SystemMessage.from("sys"), UserMessage.from("u")))
                .build();
    }
}
