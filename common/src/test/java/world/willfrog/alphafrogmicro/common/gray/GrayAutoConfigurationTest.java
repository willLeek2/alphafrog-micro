package world.willfrog.alphafrogmicro.common.gray;

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

    @Test
    void disabledByDefault_shouldProvideNoOpDeciderWithoutStoreOrVersionLog(CapturedOutput output) {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GrayDecider.class);
            assertThat(context).doesNotHaveBean(GrayRuleStore.class);
            assertThat(context.getBean(GrayDecider.class).isEnabled("any-rule", "any-user")).isFalse();
        });

        assertThat(output).doesNotContain("Loaded gray rule version=");
    }

    @Test
    void explicitFalseAndInvalidValue_shouldFailClosedWithNoOpDecider() {
        runner().withPropertyValues("alphafrog.gray.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(GrayDecider.class);
            assertThat(context).doesNotHaveBean(GrayRuleStore.class);
            assertThat(context.getBean(GrayDecider.class).isEnabled("any-rule", "any-user")).isFalse();
        });
        runner().withPropertyValues("alphafrog.gray.enabled=unexpected").run(context -> {
            assertThat(context).hasSingleBean(GrayDecider.class);
            assertThat(context).doesNotHaveBean(GrayRuleStore.class);
            assertThat(context.getBean(GrayDecider.class).isEnabled("any-rule", "any-user")).isFalse();
        });
    }

    @Test
    void enabledWithMissingFile_shouldStartWithEmptySnapshot() {
        runner().withPropertyValues(
                "alphafrog.gray.enabled=true",
                "alphafrog.gray.rules-file=/path/that/does/not/exist/gray-rules.local.json"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GrayRuleStore.class);
            assertThat(context).hasSingleBean(GrayDecider.class);
            assertThat(context.getBean(GrayRuleStore.class).currentRuleVersion()).isEmpty();
            assertThat(context.getBean(GrayDecider.class).isEnabled("any-rule", "any-user")).isFalse();
        });
    }

    @Test
    void enabledWithValidFile_shouldLoadDynamicDecider() throws Exception {
        Path file = Files.createTempFile("gray-rules-", ".json");
        try {
            Files.writeString(file, validDocument(), StandardCharsets.UTF_8);
            runner().withPropertyValues(
                    "alphafrog.gray.enabled=true",
                    "alphafrog.gray.rules-file=" + file,
                    "spring.application.name=gray-auto-config-test",
                    "spring.application.instance-id=instance-a"
            ).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(GrayRuleStore.class);
                assertThat(context).hasSingleBean(GrayDecider.class);
                assertThat(context.getBean(GrayRuleStore.class).currentRuleVersion())
                        .contains("2026-09-02-01");
                assertThat(context.getBean(GrayDecider.class)
                        .isEnabled("allowlist-rule", "allowlisted-user")).isTrue();
            });
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void serviceProvidedDecider_shouldOverrideTheDefaultBean() {
        runner().withUserConfiguration(OverrideConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(GrayDecider.class);
            assertThat(context.getBean(GrayDecider.class)).isSameAs(OverrideConfiguration.CUSTOM);
        });
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
