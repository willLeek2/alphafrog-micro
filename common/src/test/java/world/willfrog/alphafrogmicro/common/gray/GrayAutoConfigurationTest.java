package world.willfrog.alphafrogmicro.common.gray;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GrayAutoConfigurationTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GrayAutoConfiguration.class));
    }








    private GrayRuleStore loadedStore(Path file) throws Exception {
        Files.writeString(file, validDocument(), StandardCharsets.UTF_8);
        GrayRuleStore store = new GrayRuleStore(
                new ObjectMapper(),
                file,
                60_000L,
                "gray-auto-config-test",
                "instance-custom");
        assertThat(store.refreshNow()).isTrue();
        return store;
    }

    private String validDocument() {
        return """
                {
                  "ruleVersion": "2026-09-02-01",
                  "bucketSalt": "salt-a",
                  "rules": [
                    {
                      "ruleId": "allowlist-rule",
                      "enabled": true,
                      "percent": 0,
                      "userFilter": ["allowlisted-user"],
                      "owner": "platform",
                      "expiresAt": "2099-01-01T00:00:00Z"
                    }
                  ]
                }
                """;
    }

    @Configuration(proxyBeanMethods = false)
    static class OverrideConfiguration {
        private static final GrayDecider CUSTOM = GrayDecider.disabled();

        @Bean
        GrayDecider customGrayDecider() {
            return CUSTOM;
        }
    }
}
