package world.willfrog.alphafrogmicro.frontend.service.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthObservabilityWriterTest {

    private AuthObservabilityProperties properties(Path tempDir) {
        AuthObservabilityProperties props = new AuthObservabilityProperties();
        props.setOutputRoot(tempDir.toString());
        props.getAuth().setMaxFileSizeBytes(200L);
        props.getAuth().setMaxBytesPerSession(1000L);
        return props;
    }

    private AuthObservabilitySession session(Path tempDir, String sessionId) {
        return new AuthObservabilitySession(
                sessionId, System.currentTimeMillis(),
                System.currentTimeMillis() + 3600_000L,
                tempDir.resolve(sessionId).toString(),
                "admin", "test", new AuthObservabilityScope());
    }




}
