package world.willfrog.beta.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SecretFileGitignoreTest {
    @Test
    void productionDotenvStaysOutOfGit() throws Exception {
        Path root = gitRoot();
        Path gitignore = root.resolve(".gitignore");
        assertTrue(Files.readAllLines(gitignore).stream().anyMatch(".env"::equals),
                ".gitignore must exclude the production .env file by exact name");

        Process ignore = new ProcessBuilder("git", "check-ignore", "-q", ".env")
                .directory(root.toFile())
                .start();
        assertEquals(0, ignore.waitFor(), "git check-ignore must match .env");

        Process tracked = new ProcessBuilder("git", "ls-files", "--error-unmatch", "--", ".env")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        tracked.getInputStream().readAllBytes();
        assertEquals(1, tracked.waitFor(), "git must not track .env");
    }

    private Path gitRoot() throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                .directory(Path.of("").toAbsolutePath().toFile())
                .start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        assertEquals(0, process.waitFor(), "git rev-parse must locate the repository");
        return Path.of(output);
    }
}
