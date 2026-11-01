package world.willfrog.beta.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SecretFileGitignoreTest {

    private Path gitRoot() throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                .directory(Path.of("").toAbsolutePath().toFile())
                .start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        assertEquals(0, process.waitFor(), "git rev-parse must locate the repository");
        return Path.of(output);
    }
}
