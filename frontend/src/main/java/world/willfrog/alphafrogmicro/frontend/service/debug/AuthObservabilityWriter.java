package world.willfrog.alphafrogmicro.frontend.service.debug;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class AuthObservabilityWriter implements AutoCloseable {

    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "bearer ", "authorization:", "eyj", "sk-", "sk_"
    );

    private final ObjectMapper objectMapper;
    private final AuthObservabilitySession session;
    private final AuthObservabilityProperties properties;
    private final String serviceName;
    private final String hostName;

    private final ReentrantLock lock = new ReentrantLock();
    private BufferedWriter currentWriter;
    private int fileIndex = 0;
    private long currentFileBytes = 0;
    private boolean closed = false;

    public AuthObservabilityWriter(ObjectMapper objectMapper,
                                    AuthObservabilitySession session,
                                    AuthObservabilityProperties properties) {
        this.objectMapper = objectMapper;
        this.session = session;
        this.properties = properties;
        this.serviceName = "alphafrog-frontend";
        this.hostName = resolveHostName();
        ensureDirectory();
        writeManifest();
    }

    public void writeEvent(String eventType, Map<String, Object> payload) {
        if (closed || !session.isEnabled()) {
            return;
        }
        if (session.isExpired(System.currentTimeMillis())) {
            closeWithReason("TTL_EXPIRED");
            return;
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now().toString());
        event.put("debugSessionId", session.getDebugSessionId());
        event.put("service", serviceName);
        event.put("host", hostName);
        event.put("eventType", eventType);
        if (payload != null) {
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                event.put(entry.getKey(), entry.getValue());
            }
        }

        String line;
        try {
            line = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            session.getWriterErrorCount().incrementAndGet();
            return;
        }

        if (containsSensitiveToken(line)) {
            session.getDroppedBySensitiveFilter().incrementAndGet();
            return;
        }

        byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
        long totalBytes = session.getBytesWritten().get();
        if (totalBytes + lineBytes.length > properties.getAuth().getMaxBytesPerSession()) {
            session.getDroppedByCapacity().incrementAndGet();
            closeWithReason("MAX_BYTES");
            return;
        }

        lock.lock();
        try {
            if (closed || !session.isEnabled()) {
                session.getDroppedByCapacity().incrementAndGet();
                return;
            }
            openWriterIfNeeded(lineBytes.length);
            if (currentWriter == null) {
                session.getWriterErrorCount().incrementAndGet();
                return;
            }
            currentWriter.write(line);
            currentWriter.newLine();
            currentWriter.flush();
            session.getBytesWritten().addAndGet(lineBytes.length);
            session.getLinesWritten().incrementAndGet();
            currentFileBytes += lineBytes.length;
        } catch (IOException e) {
            session.getWriterErrorCount().incrementAndGet();
            log.warn("Failed to write auth observability event", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    @PreDestroy
    public void close() {
        closeWithReason("DISABLED");
    }

    public void closeWithReason(String reason) {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            session.setEnabled(false);
            session.setStoppedReason(reason);
            if (currentWriter != null) {
                try {
                    currentWriter.flush();
                    currentWriter.close();
                } catch (IOException e) {
                    log.warn("Failed to close auth observability writer", e);
                } finally {
                    currentWriter = null;
                }
            }
            writeManifest();
            closed = true;
        } finally {
            lock.unlock();
        }
    }

    private void openWriterIfNeeded(long lineLength) throws IOException {
        long maxFileBytes = properties.getAuth().getMaxFileSizeBytes();
        if (currentWriter != null && currentFileBytes + lineLength <= maxFileBytes) {
            return;
        }
        if (currentWriter != null) {
            currentWriter.flush();
            currentWriter.close();
            fileIndex++;
            currentFileBytes = 0;
        }
        Path file = getCurrentAuthFile();
        currentWriter = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private Path getCurrentAuthFile() {
        return Paths.get(session.getOutputDir(), "auth-" + fileIndex + ".jsonl");
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(Paths.get(session.getOutputDir()));
        } catch (IOException e) {
            log.error("Failed to create auth observability output directory: {}", session.getOutputDir(), e);
        }
    }

    private void writeManifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("debugSessionId", session.getDebugSessionId());
        manifest.put("service", serviceName);
        manifest.put("host", hostName);
        manifest.put("createdAt", Instant.ofEpochMilli(session.getCreatedAt()).toString());
        manifest.put("ttlDeadline", Instant.ofEpochMilli(session.getTtlDeadline()).toString());
        manifest.put("enabled", session.isEnabled());
        manifest.put("stoppedReason", session.getStoppedReason());
        manifest.put("operator", session.getOperator());
        manifest.put("reason", session.getReason());
        manifest.put("outputDir", session.getOutputDir());
        manifest.put("scope", session.getScope());
        manifest.put("bytesWritten", session.getBytesWritten().get());
        manifest.put("linesWritten", session.getLinesWritten().get());
        manifest.put("droppedByCapacity", session.getDroppedByCapacity().get());
        manifest.put("droppedBySensitiveFilter", session.getDroppedBySensitiveFilter().get());
        manifest.put("writerErrorCount", session.getWriterErrorCount().get());

        try {
            Path manifestFile = Paths.get(session.getOutputDir(), "manifest.json");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(manifestFile.toFile(), manifest);
        } catch (IOException e) {
            log.error("Failed to write auth observability manifest", e);
        }
    }

    private boolean containsSensitiveToken(String line) {
        String lower = line.toLowerCase();
        for (String token : FORBIDDEN_TOKENS) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
