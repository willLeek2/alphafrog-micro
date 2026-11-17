package world.willfrog.alphafrogmicro.common.service.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigActiveDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigAuditLogDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigSnapshotDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigTypeDao;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigPublishException;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigActive;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigSnapshot;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigProfileServiceModelChangeTest {

    @Mock
    private ConfigTypeDao configTypeDao;
    @Mock
    private ConfigSnapshotDao configSnapshotDao;
    @Mock
    private ConfigActiveDao configActiveDao;
    @Mock
    private ConfigAuditLogDao configAuditLogDao;
    @Mock
    private ConfigService nacosConfigService;
    @Mock
    private StringRedisTemplate redisTemplate;

    private ConfigProfileService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ConfigProfileService(
                configTypeDao,
                configSnapshotDao,
                configActiveDao,
                configAuditLogDao,
                objectMapper,
                nacosConfigService,
                redisTemplate);
    }

    @Test
    void blankRunningDocumentDoesNotWriteSnapshot() throws Exception {
        when(configTypeDao.getByName("agent-llm")).thenReturn(agentLlmType());
        when(nacosConfigService.getConfig("agent-llm.json", "alphafrog-config", 3000)).thenReturn("  ");

        assertThrows(ConfigPublishException.class, () -> service.changeAgentModel(
                "agent-llm",
                AgentLlmModelCatalog.Change.CREATE,
                "qwen4-max",
                objectMapper.readTree("[{\"endpoint\":\"dashscope-cn\",\"displayName\":\"Qwen4-Max\",\"baseRate\":0.35}]"),
                "7"));

        verify(configSnapshotDao, never()).insert(any());
        verify(nacosConfigService, never()).publishConfig(any(), any(), any());
    }

    @Test
    void otherConfigTypeIsRejectedBeforeReadingNacos() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.changeAgentModel(
                "code-refine",
                AgentLlmModelCatalog.Change.DELETE,
                "qwen4-max",
                null,
                "7"));
        verify(nacosConfigService, never()).getConfig(any(), any(), anyLong());
    }

    @Test
    void createPublishesWholeDocumentBasedOnCurrentNacos() throws Exception {
        ConfigType type = agentLlmType();
        when(configTypeDao.getByName("agent-llm")).thenReturn(type);
        when(configTypeDao.lockById(type.getId())).thenReturn(type);
        when(nacosConfigService.getConfig("agent-llm.json", "alphafrog-config", 3000)).thenReturn("""
                {"defaultModel":"moonshotai/kimi-k3","models":["moonshotai/kimi-k3"],
                 "endpoints":{"dashscope-cn":{"apiKey":"secret","models":{}}}}
                """);
        when(configSnapshotDao.maxVersionNumberByType(type.getId())).thenReturn(1);
        ConfigSnapshot saved = new ConfigSnapshot();
        saved.setId(20);
        saved.setTypeId(type.getId());
        saved.setVersion("v2");
        doAnswer(invocation -> {
            ConfigSnapshot inserted = invocation.getArgument(0);
            saved.setContentJson(inserted.getContentJson());
            return null;
        }).when(configSnapshotDao).insert(any());
        when(configSnapshotDao.getByTypeAndVersion(type.getId(), "v2")).thenReturn(saved);
        ConfigActive active = new ConfigActive();
        active.setTypeId(type.getId());
        active.setSnapshotId(9);
        when(configActiveDao.getByType(type.getId())).thenReturn(active);
        when(configActiveDao.updateIfSnapshotMatches(eq(type.getId()), eq(20), eq(9), any(), eq("7"))).thenReturn(1);
        when(nacosConfigService.publishConfig(eq("agent-llm.json"), eq("alphafrog-config"), any())).thenReturn(true);

        Map<String, Object> result = service.changeAgentModel(
                "agent-llm",
                AgentLlmModelCatalog.Change.CREATE,
                "qwen4-max",
                objectMapper.readTree("[{\"endpoint\":\"dashscope-cn\",\"displayName\":\"Qwen4-Max\",\"baseRate\":0.35}]"),
                "7");

        assertEquals("v2", result.get("version"));
        assertEquals(20, result.get("snapshotId"));
        ArgumentCaptor<String> published = ArgumentCaptor.forClass(String.class);
        verify(nacosConfigService).publishConfig(eq("agent-llm.json"), eq("alphafrog-config"), published.capture());
        assertTrue(published.getValue().contains("qwen4-max"));
        assertTrue(published.getValue().contains("moonshotai/kimi-k3"));
        assertTrue(published.getValue().contains("secret"));
    }

    private ConfigType agentLlmType() {
        ConfigType type = new ConfigType();
        type.setId(4);
        type.setName("agent-llm");
        type.setDataId("agent-llm.json");
        type.setConfigGroup("alphafrog-config");
        return type;
    }
}
