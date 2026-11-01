package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import world.willfrog.agent.platform.context.AgentContext;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.util.PromptFileLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLlmLocalConfigLoaderTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearModelContext() { AgentContext.clear(); }









































    /** 金融方法解析测试使用实际加载器、解析器、模型服务和版本渲染；版本读取、路由、模型响应及输入保存采用替身。 */
    private record RealResolverChain(
            AgentLlmLocalConfigLoader loader,
            FinanceMethodResolverModelResolver modelResolver,
            FinanceMethodResolverModelService service,
            dev.langchain4j.model.chat.ChatModel chatModel) {
    }

    private RealResolverChain realChain(Path configFile, AgentLlmProperties staticProperties) {
        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        FinanceMethodResolverModelResolver modelResolver =
                new FinanceMethodResolverModelResolver(staticProperties, loader);

        AgentAiServiceFactory aiServiceFactory = org.mockito.Mockito.mock(AgentAiServiceFactory.class);
        VersionedModelTestFixture versioned = new VersionedModelTestFixture();
        versioned.put("financeMethodResolverSystemPrompt", "resolver template {{RESOLVER_CATALOG}}", "RESOLVER_CATALOG");
        AgentPromptService promptService = versioned.promptService(staticProperties, loader);
        AgentRunObservabilityService observability = org.mockito.Mockito.mock(AgentRunObservabilityService.class);
        dev.langchain4j.model.chat.ChatModel chatModel = org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
        AgentLlmResolver.ResolvedLlm resolved = new AgentLlmResolver.ResolvedLlm(
                "local-endpoint", "https://local.example.com/v1", "local-model", "key", "", java.util.List.of(), null);
        org.mockito.Mockito.when(aiServiceFactory.resolveLlm(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(resolved);
        org.mockito.Mockito.when(aiServiceFactory.buildArchivedChatModelWithProviderOrderAndTemperature(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(versioned.archived(chatModel, "local-endpoint"));
        org.mockito.Mockito.when(chatModel.chat(org.mockito.ArgumentMatchers.anyList())).thenReturn(
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(new dev.langchain4j.data.message.AiMessage("{\"status\":\"NO_ADVICE\",\"candidates\":[]}"))
                        .build());

        FinanceMethodResolverModelService service = new FinanceMethodResolverModelService(
                new ObjectMapper(), aiServiceFactory, modelResolver, promptService, observability);
        versioned.inject(service);
        return new RealResolverChain(loader, modelResolver, service, chatModel);
    }






    private static String authorityText(String path) {
        String text = PromptFileLoader.load(path);
        assertFalse(text.isBlank(), "测试所需权威 Prompt 必须存在: " + path);
        return text;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("agentPlatformShared"))
                    && Files.isDirectory(current.resolve("agentLangchainService"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
