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

    @Test
    void firstSuccessfulDocument_shouldReplaceEmptySnapshotOnlyAfterFullValidation() throws IOException {
        Path file = tempDir.resolve("gray-rules.local.json");
        try (GrayRuleStore store = newStore(file)) {
            assertThat(store.currentRuleVersion()).isEmpty();
            assertThat(store.find(RULE_ID)).isEmpty();

            Files.writeString(file, "{broken", StandardCharsets.UTF_8);
            assertThat(store.refreshNow()).isFalse();
            assertThat(store.currentRuleVersion()).isEmpty();
            assertThat(store.find(RULE_ID)).isEmpty();

            Files.writeString(file, validDocument(FIRST_VERSION, "salt-a", 10), StandardCharsets.UTF_8);
            assertThat(store.refreshNow()).isTrue();
            assertThat(store.currentRuleVersion()).contains(FIRST_VERSION);
            assertThat(store.find(RULE_ID)).get().extracting(GrayRuleDefinition::getPercent).isEqualTo(10);
        }
    }

    @Test
    void invalidRefreshesAndMissingFile_shouldRetainLastValidSnapshot() throws IOException {
        Path file = tempDir.resolve("gray-rules.local.json");
        Files.writeString(file, validDocument(FIRST_VERSION, "salt-a", 10), StandardCharsets.UTF_8);
        try (GrayRuleStore store = newStore(file)) {
            assertThat(store.refreshNow()).isTrue();

            List<String> invalidDocuments = List.of(
                    "",
                    validDocument(FIRST_VERSION, "salt-a", 10).replace("\"owner\": \"platform\"", "\"owner\": \"   \""),
                    validDocument("   ", "salt-a", 10),
                    validDocument(FIRST_VERSION, "salt-a", 10).replace(
                            "\"expiresAt\": \"2099-01-01T00:00:00Z\"",
                            "\"expiresAt\": \"2099-01-01T00:00:00\""),
                    validDocument(FIRST_VERSION, "salt-a", 10).replace(
                            "\"percent\": 10,",
                            "\"percent\": 10,\n      \"unexpected\": true,"),
                    duplicateRuleDocument(FIRST_VERSION));

            for (String invalid : invalidDocuments) {
                Files.writeString(file, invalid, StandardCharsets.UTF_8);
                assertThat(store.refreshNow()).isFalse();
                assertThat(store.currentRuleVersion()).contains(FIRST_VERSION);
                assertThat(store.find(RULE_ID)).get().extracting(GrayRuleDefinition::getPercent).isEqualTo(10);
            }

            Files.delete(file);
            assertThat(store.refreshNow()).isFalse();
            assertThat(store.currentRuleVersion()).contains(FIRST_VERSION);
            assertThat(store.find(RULE_ID)).isPresent();
        }
    }

    @Test
    void validRefresh_shouldReplaceTheWholeSnapshot() throws IOException {
        Path file = tempDir.resolve("gray-rules.local.json");
        Files.writeString(file, validDocument(FIRST_VERSION, "salt-a", 10), StandardCharsets.UTF_8);
        try (GrayRuleStore store = newStore(file)) {
            assertThat(store.refreshNow()).isTrue();
            GrayRuleStore.Snapshot first = store.currentSnapshot();

            Files.writeString(file, validDocument(SECOND_VERSION, "salt-b", 40), StandardCharsets.UTF_8);
            assertThat(store.refreshNow()).isTrue();
            GrayRuleStore.Snapshot second = store.currentSnapshot();

            assertThat(first.ruleVersion()).isEqualTo(FIRST_VERSION);
            assertThat(first.bucketSalt()).isEqualTo("salt-a");
            assertThat(first.rules().get(RULE_ID).getPercent()).isEqualTo(10);
            assertThat(second.ruleVersion()).isEqualTo(SECOND_VERSION);
            assertThat(second.bucketSalt()).isEqualTo("salt-b");
            assertThat(second.rules().get(RULE_ID).getPercent()).isEqualTo(40);
        }
    }

    @Test
    void readers_shouldNeverObserveFieldsFromDifferentSnapshots() throws Exception {
        Path file = tempDir.resolve("gray-rules.local.json");
        Files.writeString(file, validDocument(FIRST_VERSION, "salt-a", 10), StandardCharsets.UTF_8);
        try (GrayRuleStore store = newStore(file)) {
            assertThat(store.refreshNow()).isTrue();

            ExecutorService readers = Executors.newFixedThreadPool(4);
            AtomicBoolean running = new AtomicBoolean(true);
            CountDownLatch started = new CountDownLatch(4);
            ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(readers.submit(() -> {
                    started.countDown();
                    while (running.get()) {
                        GrayRuleStore.Snapshot observed = store.currentSnapshot();
                        GrayRuleDefinition rule = observed.rules().get(RULE_ID);
                        if (FIRST_VERSION.equals(observed.ruleVersion())) {
                            if (!"salt-a".equals(observed.bucketSalt()) || rule == null || rule.getPercent() != 10) {
                                failures.add("mixed first snapshot");
                            }
                        } else if (SECOND_VERSION.equals(observed.ruleVersion())) {
                            if (!"salt-b".equals(observed.bucketSalt()) || rule == null || rule.getPercent() != 40) {
                                failures.add("mixed second snapshot");
                            }
                        } else {
                            failures.add("unexpected version " + observed.ruleVersion());
                        }
                    }
                }));
            }

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 20; i++) {
                boolean first = (i & 1) == 0;
                Files.writeString(
                        file,
                        first
                                ? validDocument(FIRST_VERSION, "salt-a", 10)
                                : validDocument(SECOND_VERSION, "salt-b", 40),
                        StandardCharsets.UTF_8);
                assertThat(store.refreshNow()).isTrue();
            }
            running.set(false);
            readers.shutdown();
            assertThat(readers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            assertThat(failures).isEmpty();
        }
    }

    @Test
    void scheduledPolling_shouldLoadChangedFileWithoutManualRefresh() throws Exception {
        Path file = tempDir.resolve("gray-rules.local.json");
        Files.writeString(file, validDocument(FIRST_VERSION, "salt-a", 10), StandardCharsets.UTF_8);

        try (GrayRuleStore store = new GrayRuleStore(
                objectMapper, file, 20L, "common-test", "instance-test")) {
            store.start();
            awaitRuleVersion(store, FIRST_VERSION);

            Files.writeString(file, validDocument(SECOND_VERSION, "salt-b", 40), StandardCharsets.UTF_8);
            awaitRuleVersion(store, SECOND_VERSION);

            assertThat(store.find(RULE_ID)).get()
                    .extracting(GrayRuleDefinition::getPercent)
                    .isEqualTo(40);
        }
    }

    @Test
    void packagedSchema_shouldEqualRepositoryContractAndRejectTimezoneLessValueByPattern() throws IOException {
        byte[] packaged;
        try (InputStream input = GrayRuleStore.class.getResourceAsStream(GrayRuleStore.SCHEMA_RESOURCE)) {
            assertThat(input).isNotNull();
            packaged = input.readAllBytes();
        }
        byte[] repository = Files.readAllBytes(repositoryFile("deploy/gray/gray-rules.schema.json"));
        assertThat(packaged).isEqualTo(repository);

        JsonNode schema = objectMapper.readTree(packaged);
        String timezonePattern = schema.at("/definitions/grayRule/properties/expiresAt/pattern").asText();
        assertThat(Pattern.compile(timezonePattern).matcher("2099-01-01T00:00:00").find()).isFalse();
        assertThat(Pattern.compile(timezonePattern).matcher("2099-01-01T00:00:00Z").find()).isTrue();
        assertThat(Pattern.compile(timezonePattern).matcher("2099-01-01T00:00:00+08:00").find()).isTrue();
    }

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
