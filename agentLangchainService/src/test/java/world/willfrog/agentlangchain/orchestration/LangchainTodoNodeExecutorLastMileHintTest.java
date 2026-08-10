package world.willfrog.agentlangchain.orchestration;

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

    @Test
    void nullHintShouldReturnSameChatRequest() {
        AgentContext.setLastMileHint(null);
        ChatRequest request = sampleRequest();

        ChatRequest result = LangchainTodoNodeExecutor.maybeInjectLastMileHint(request);

        assertSame(request, result, "null hint should not rebuild ChatRequest");
        assertNull(AgentContext.getLastMileHint());
    }

    @Test
    void blankHintShouldReturnSameChatRequest() {
        AgentContext.setLastMileHint("   \n  ");
        ChatRequest request = sampleRequest();

        ChatRequest result = LangchainTodoNodeExecutor.maybeInjectLastMileHint(request);

        assertSame(request, result, "blank hint should not rebuild ChatRequest");
        assertNull(AgentContext.getLastMileHint());
    }

    @Test
    void hintWithExistingSystemMessageShouldAppendNewUserMessage() {
        String hint = "[last_mile_hint] 9/10 tool_calls (90%)";
        AgentContext.setLastMileHint(hint);
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from("原始系统提示"),
                        UserMessage.from("用户问题")
                ))
                .temperature(0.7)
                .build();

        ChatRequest result = LangchainTodoNodeExecutor.maybeInjectLastMileHint(request);

        List<ChatMessage> msgs = result.messages();
        assertEquals(3, msgs.size(), "must append one UserMessage and preserve existing messages");
        ChatMessage first = msgs.get(0);
        assertTrue(first instanceof SystemMessage, "first message should still be SystemMessage");
        String text = ((SystemMessage) first).text();
        assertEquals("原始系统提示", text, "stable System must remain byte-identical");
        assertTrue(msgs.get(2) instanceof UserMessage);
        assertEquals(hint, ((UserMessage) msgs.get(2)).singleText());
        assertNull(AgentContext.getLastMileHint(), "ThreadLocal must be consumed after injection");
    }

    @Test
    void hintWithoutSystemMessageShouldAppendNewUserMessage() {
        String hint = "[last_mile_hint] 90% tokens";
        AgentContext.setLastMileHint(hint);
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        UserMessage.from("user1"),
                        AiMessage.from("ai1"),
                        UserMessage.from("user2")
                ))
                .build();

        ChatRequest result = LangchainTodoNodeExecutor.maybeInjectLastMileHint(request);

        List<ChatMessage> msgs = result.messages();
        assertEquals(4, msgs.size(), "must append one UserMessage, keep original order intact");
        ChatMessage first = msgs.get(0);
        assertTrue(first instanceof UserMessage, "original first message must remain first");
        assertEquals("user1", ((UserMessage) msgs.get(0)).singleText());
        assertEquals("ai1", ((AiMessage) msgs.get(1)).text());
        assertEquals("user2", ((UserMessage) msgs.get(2)).singleText());
        assertEquals(hint, ((UserMessage) msgs.get(3)).singleText());
        assertNull(AgentContext.getLastMileHint());
    }

    @Test
    void rebuildShouldPreserveTemperatureAndToolSpecifications() {
        AgentContext.setLastMileHint("[last_mile_hint] hint");
        ToolSpecification tool = ToolSpecification.builder()
                .name("t1")
                .description("d1")
                .build();
        // LC4j ChatRequest validation forbids setting both `parameters` and `temperature`:
        // here we set the typical AiServices runtime shape (temperature + toolSpecifications).
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(SystemMessage.from("sys"), UserMessage.from("u")))
                .temperature(0.42)
                .toolSpecifications(List.of(tool))
                .build();

        ChatRequest result = LangchainTodoNodeExecutor.maybeInjectLastMileHint(request);

        assertEquals(0.42, result.temperature(), 0.0001);
        assertNotNull(result.toolSpecifications());
        assertEquals(1, result.toolSpecifications().size());
        assertEquals("t1", result.toolSpecifications().get(0).name());
    }

    @Test
    void hintShouldBeConsumedExactlyOnceEvenIfTransformedAgain() {
        AgentContext.setLastMileHint("[last_mile_hint] 1");
        ChatRequest request = sampleRequest();

        ChatRequest first = LangchainTodoNodeExecutor.maybeInjectLastMileHint(request);
        assertNull(AgentContext.getLastMileHint(), "first call must clear the hint");

        ChatRequest second = LangchainTodoNodeExecutor.maybeInjectLastMileHint(first);
        assertSame(first, second, "second call (hint=null) should be no-op and return same ChatRequest");
    }

    @Test
    void nullChatRequestShouldReturnNull() {
        AgentContext.setLastMileHint("[last_mile_hint] hint");
        assertNull(LangchainTodoNodeExecutor.maybeInjectLastMileHint(null));
    }

    private ChatRequest sampleRequest() {
        return ChatRequest.builder()
                .messages(List.of(SystemMessage.from("sys"), UserMessage.from("u")))
                .build();
    }
}
