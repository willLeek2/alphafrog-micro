package world.willfrog.alphafrogmicro.common.gray;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class GrayRuleStoreTest {

    private static final String RULE_ID = "demo-rule";
    private static final String FIRST_VERSION = "2026-09-02-01";
    private static final String SECOND_VERSION = "2026-09-02-02";

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();







    private GrayRuleStore newStore(Path file) {
        return new GrayRuleStore(objectMapper, file, 60_000L, "common-test", "instance-test");
    }

    private void awaitRuleVersion(GrayRuleStore store, String expected) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadlineNanos) {
            if (store.currentRuleVersion().filter(expected::equals).isPresent()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertThat(store.currentRuleVersion()).contains(expected);
    }

    private String validDocument(String version, String salt, int percent) {
        return """
                {
                  "ruleVersion": "%s",
                  "bucketSalt": "%s",
                  "rules": [
                    {
                      "ruleId": "demo-rule",
                      "enabled": true,
                      "percent": %d,
                      "userFilter": ["allowlisted-user"],
                      "owner": "platform",
                      "expiresAt": "2099-01-01T00:00:00Z"
                    }
                  ]
                }
                """.formatted(version, salt, percent);
    }

    private String duplicateRuleDocument(String version) {
        return """
                {
                  "ruleVersion": "%s",
                  "bucketSalt": "salt-a",
                  "rules": [
                    {
                      "ruleId": "demo-rule",
                      "enabled": true,
                      "percent": 10,
                      "userFilter": [],
                      "owner": "platform",
                      "expiresAt": "2099-01-01T00:00:00Z"
                    },
                    {
                      "ruleId": "demo-rule",
                      "enabled": true,
                      "percent": 20,
                      "userFilter": [],
                      "owner": "platform",
                      "expiresAt": "2099-01-01T00:00:00Z"
                    }
                  ]
                }
                """.formatted(version);
    }

    private Path repositoryFile(String relativePath) {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            Path candidate = cursor.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Repository file not found: " + relativePath);
    }
}
