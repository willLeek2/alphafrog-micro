package world.willfrog.alphafrogmicro.common.service.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import world.willfrog.alphafrogmicro.common.config.ConfigJsonCanonicalizer;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigActiveDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigAuditLogDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigSnapshotDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigTypeDao;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigConflictException;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigValidationException;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigActive;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigAuditLog;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigSnapshot;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class ConfigProfileServiceTest {

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

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ConfigProfileService configProfileService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        configProfileService = new ConfigProfileService(
                configTypeDao,
                configSnapshotDao,
                configActiveDao,
                configAuditLogDao,
                objectMapper,
                nacosConfigService,
                redisTemplate
        );
    }



















    private ConfigSnapshot deriveAndCapture(String baseJson, String patchJson) throws Exception {
        ConfigType type = buildType();
        ConfigSnapshot base = buildSnapshot();
        base.setId(1);
        base.setVersion("v1");
        base.setContentJson(baseJson);

        when(configTypeDao.getByName("code-refine")).thenReturn(type);
        when(configSnapshotDao.getByTypeAndVersion(type.getId(), "v1")).thenReturn(base);
        when(configSnapshotDao.maxVersionNumberByType(type.getId())).thenReturn(1);
        when(configTypeDao.lockById(type.getId())).thenReturn(type);

        configProfileService.derive(
                "code-refine",
                "v1",
                "ops",
                objectMapper.readTree(patchJson),
                "test ops",
                "7",
                true
        );

        ArgumentCaptor<ConfigSnapshot> captor = ArgumentCaptor.forClass(ConfigSnapshot.class);
        verify(configSnapshotDao).insert(captor.capture());
        return captor.getValue();
    }

    private ConfigType buildType() {
        ConfigType type = new ConfigType();
        type.setId(1);
        type.setName("code-refine");
        type.setDataId("code-refine.json");
        type.setConfigGroup("alphafrog-config");
        return type;
    }

    private ConfigType buildOverlayType() {
        ConfigType type = new ConfigType();
        type.setId(11);
        type.setName("agent-prompt-overlay");
        type.setDataId("agent-prompt-overlay.json");
        type.setConfigGroup("alphafrog-config");
        type.setSchemaJson("");
        return type;
    }

    private ConfigSnapshot buildSnapshot() {
        ConfigSnapshot snapshot = new ConfigSnapshot();
        snapshot.setId(2);
        snapshot.setTypeId(1);
        snapshot.setVersion("v2");
        snapshot.setContentJson("{\"maxAttempts\":5}");
        snapshot.setContentMd5("abc123");
        return snapshot;
    }
}
