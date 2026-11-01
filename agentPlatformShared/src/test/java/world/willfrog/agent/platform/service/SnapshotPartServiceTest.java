package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import world.willfrog.agent.platform.config.AgentSnapshotProperties;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnapshotPartServiceTest {

    @Mock
    private RedisTemplate<String, byte[]> snapshotPartRedisTemplate;
    @Mock
    private ValueOperations<String, byte[]> valueOperations;

    private SnapshotPartService createService(AgentSnapshotProperties properties) {
        lenient().when(snapshotPartRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
        return new SnapshotPartService(properties, snapshotPartRedisTemplate, new ObjectMapper());
    }





    private AgentSnapshotProperties baseProperties() {
        AgentSnapshotProperties properties = new AgentSnapshotProperties();
        properties.setDefaultPartSize(128);
        properties.setMinPartSize(64);
        properties.setMaxPartSize(512);
        properties.setCacheTtlSeconds(60);
        properties.setGzipEnabled(true);
        return properties;
    }

    private byte[] toBytes(SnapshotPartsMeta meta) {
        try {
            return new ObjectMapper().writeValueAsBytes(meta);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
