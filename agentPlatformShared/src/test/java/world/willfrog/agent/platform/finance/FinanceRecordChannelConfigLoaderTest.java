package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceRecordChannelConfigLoaderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missingFileFallsBackToApplicationDefaults() {
        FinanceRecordChannelProperties defaults = new FinanceRecordChannelProperties();
        defaults.setEnabled(false);
        defaults.setConfigFile("/path/that/does/not/exist.json");
        FinanceRecordChannelConfigLoader loader = new FinanceRecordChannelConfigLoader(objectMapper, defaults);

        loader.load();

        assertThat(loader.current().limits().enabled()).isFalse();
        assertThat(loader.current().limits().recordCountMax()).isEqualTo(128);
        assertThat(loader.current().sourceRevision()).isEqualTo("application-defaults");
    }

    @Test
    void dynamicValuesOverrideDefaultsButCannotExpandHardLimits(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("finance-record-channel.json");
        Files.writeString(config, """
                {
                  "enabled": true,
                  "recordCountMax": 999999,
                  "recordMaxBytes": 2048,
                  "recordChannelMaxBytes": 4096,
                  "stdoutMaxBytes": 8192,
                  "stderrMaxBytes": 1024,
                  "targetEnvironment": {
                    "environmentId": "sha256:target",
                    "imageDigest": "sha256:image",
                    "librarySetDigest": "sha256:library",
                    "packageApis": [{"name":"alphafrog_finance","version":"1.0.3","apiVersion":"1.0"}]
                  }
                }
                """);
        FinanceRecordChannelProperties defaults = new FinanceRecordChannelProperties();
        defaults.setConfigFile(config.toString());
        FinanceRecordChannelConfigLoader loader = new FinanceRecordChannelConfigLoader(objectMapper, defaults);

        loader.load();

        assertThat(loader.current().limits().enabled()).isTrue();
        assertThat(loader.current().limits().recordCountMax())
                .isEqualTo(FinanceRecordChannelProperties.HARD_RECORD_COUNT_MAX);
        assertThat(loader.current().limits().recordMaxBytes()).isEqualTo(2048);
        assertThat(loader.current().limitsClamped()).isTrue();
        assertThat(loader.current().targetEnvironment().environmentId()).isEqualTo("sha256:target");
        assertThat(loader.current().sourceRevision()).startsWith("sha256:");

        String frozen = loader.frozenSnapshotJson();
        JsonNode root = objectMapper.readTree(frozen);
        assertThat(root.path("effectiveFinanceRecordConfig").path("targetEnvironmentId").asText())
                .isEqualTo("sha256:target");
        assertThat(root.toString()).doesNotContain("imageDigest", "packageApis", "librarySetDigest");
        assertThat(loader.parseFrozenLimits(frozen)).isEqualTo(loader.current().limits());
    }

    @Test
    void invalidReloadKeepsLastValidSnapshot(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("finance-record-channel.json");
        Files.writeString(config, "{\"enabled\":true,\"recordCountMax\":12}");
        FinanceRecordChannelProperties defaults = new FinanceRecordChannelProperties();
        defaults.setConfigFile(config.toString());
        FinanceRecordChannelConfigLoader loader = new FinanceRecordChannelConfigLoader(objectMapper, defaults);
        loader.load();
        FinanceRecordChannelConfigLoader.Snapshot valid = loader.current();

        Files.writeString(config, "{broken");
        loader.reloadIfNeeded(true);

        assertThat(loader.current()).isSameAs(valid);
    }
}
