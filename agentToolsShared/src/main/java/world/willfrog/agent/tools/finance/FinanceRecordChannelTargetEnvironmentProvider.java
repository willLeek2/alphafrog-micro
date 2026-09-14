package world.willfrog.agent.tools.finance;

import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.finance.FinanceEnvironmentFact;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;

import java.util.List;
import java.util.Optional;

/**
 * Exposes the record channel's validated target-environment inventory to the MethodSpec
 * suggestion renderer. The model and tool arguments never supply these facts.
 */
@Component
public final class FinanceRecordChannelTargetEnvironmentProvider
        implements FinanceTargetEnvironmentProvider {

    private final FinanceRecordChannelConfigLoader configLoader;

    public FinanceRecordChannelTargetEnvironmentProvider(
            FinanceRecordChannelConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @Override
    public Optional<FinanceMethodSuggestionRenderer.TargetEnvironment>
            currentTargetEnvironment() {
        FinanceEnvironmentFact fact = configLoader.current().targetEnvironment();
        if (fact == null) {
            return Optional.empty();
        }
        List<FinanceMethodSuggestionRenderer.TargetEnvironment.PackageApi> packages =
                fact.packageApis().stream()
                        .map(item -> new FinanceMethodSuggestionRenderer.TargetEnvironment.PackageApi(
                                item.name(), item.version(), item.apiVersion()))
                        .toList();
        return Optional.of(new FinanceMethodSuggestionRenderer.TargetEnvironment(
                fact.environmentId(), packages));
    }
}
