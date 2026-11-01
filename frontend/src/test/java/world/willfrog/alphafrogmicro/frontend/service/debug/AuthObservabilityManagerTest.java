package world.willfrog.alphafrogmicro.frontend.service.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuthObservabilityManagerTest {

    private AuthObservabilityProperties properties(Path tempDir) {
        AuthObservabilityProperties props = new AuthObservabilityProperties();
        props.setOutputRoot(tempDir.toString());
        return props;
    }











}
