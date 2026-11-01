package world.willfrog.build.methodspec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodSpecCompilerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path resource(String path) {
        return Path.of("src/test/resources").resolve(path);
    }






}
