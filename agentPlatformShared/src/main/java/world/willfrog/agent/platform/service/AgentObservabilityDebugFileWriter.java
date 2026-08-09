package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentObservabilityDebugFileWriter {

    @Value("${agent.observability.debug-file.enabled:false}")
    private boolean enabled;

    /**
     * D04：debug 文件路径经统一存储门面解析（新键 agent.storage.observability-debug-file，
     * 旧键别名 agent.observability.debug-file.path，默认 /data/logs/agent-observability-debug.log）。
     */
    private final AgentStoragePaths storagePaths;

    private final ObjectMapper objectMapper;
    private final Object lock = new Object();
    private volatile boolean warned = false;

    public void write(String type, Map<String, Object> payload) {
        if (!enabled || payload == null) {
            return;
        }
        Path output = resolvePath();
        if (output == null) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("time", OffsetDateTime.now().toString());
        line.put("type", type == null ? "" : type);
        line.put("payload", payload);
        String text = safeWrite(line) + System.lineSeparator();
        synchronized (lock) {
            try {
                Files.writeString(
                        output,
                        text,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (Exception e) {
                warnOnce("Write observability debug file failed: " + e.getMessage(), e);
            }
        }
    }

    private Path resolvePath() {
        // debug sink 是 best-effort 遥测：路径不可达按既有 warn-once 信号降级，
        // 不向上传播异常打断 run（与 D04 前的行为一致）。
        Path output = storagePaths.observabilityDebugFile().normalize();
        try {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return output;
        } catch (Exception e) {
            warnOnce("Prepare observability debug file path failed: " + e.getMessage(), e);
            return null;
        }
    }

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void warnOnce(String message, Exception e) {
        if (warned) {
            return;
        }
        warned = true;
        if (e == null) {
            log.warn(message);
        } else {
            log.warn(message, e);
        }
    }
}

