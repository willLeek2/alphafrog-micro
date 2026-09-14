package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceRecordChannelTargetEnvironmentProviderTest {

    @Test
    void mapsTheValidatedRecordChannelTargetInventory() {
        FinanceRecordChannelProperties defaults = new FinanceRecordChannelProperties();
        FinanceRecordChannelProperties.TargetEnvironment target =
                new FinanceRecordChannelProperties.TargetEnvironment();
        target.setEnvironmentId("sha256:environment");
        target.setImageDigest("sha256:image");
        target.setLibrarySetDigest("sha256:libraries");
        FinanceRecordChannelProperties.PackageApi packageApi =
                new FinanceRecordChannelProperties.PackageApi();
        packageApi.setName("alphafrog_finance");
        packageApi.setVersion("1.0.0");
        packageApi.setApiVersion("1.0.0");
        target.setPackageApis(List.of(packageApi));
        defaults.setTargetEnvironment(target);

        FinanceRecordChannelTargetEnvironmentProvider provider =
                new FinanceRecordChannelTargetEnvironmentProvider(
                        new FinanceRecordChannelConfigLoader(new ObjectMapper(), defaults));

        assertThat(provider.currentTargetEnvironment()).hasValueSatisfying(environment -> {
            assertThat(environment.environmentId()).isEqualTo("sha256:environment");
            assertThat(environment.packageApis()).containsExactly(
                    new FinanceMethodSuggestionRenderer.TargetEnvironment.PackageApi(
                            "alphafrog_finance", "1.0.0", "1.0.0"));
        });
    }

    @Test
    void returnsEmptyWhenNoTargetEnvironmentIsConfigured() {
        FinanceRecordChannelTargetEnvironmentProvider provider =
                new FinanceRecordChannelTargetEnvironmentProvider(
                        new FinanceRecordChannelConfigLoader(
                                new ObjectMapper(), new FinanceRecordChannelProperties()));

        assertThat(provider.currentTargetEnvironment()).isEmpty();
    }
}
