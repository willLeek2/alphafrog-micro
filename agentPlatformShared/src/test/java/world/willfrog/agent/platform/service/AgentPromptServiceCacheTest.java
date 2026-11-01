package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.config.AgentLlmProperties;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AgentPromptService 的 system prompt 组装与时间基准测试。
 */
@ExtendWith(MockitoExtension.class)
class AgentPromptServiceCacheTest {

    private static final String GLOBAL_PROMPT = "你是专业金融分析代理。请只使用当前 User Message 与运行时 ToolSpecification 实际列出的工具获取数据并准确回答用户问题。";
    private static final DateTimeFormatter CN_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;

    private AgentPromptService promptService;

    @BeforeEach
    void setUp() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Prompts prompts = new AgentLlmProperties.Prompts();
        properties.setPrompts(prompts);
        lenient().when(localConfigLoader.current()).thenReturn(Optional.empty());
        promptService = new AgentPromptService(properties, localConfigLoader);
    }








    // ─── ReAct 统一 System Prompt + Stage Instruction 测试 ───












}
