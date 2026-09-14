package world.willfrog.agent.platform.finance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Low-cardinality backend metrics; no run, record, digest, or environment identifiers become tags. */
@Component
public class FinanceRecordChannelObservability {

    private final MeterRegistry registry;

    public FinanceRecordChannelObservability(ObjectProvider<MeterRegistry> provider) {
        this.registry = provider.getIfAvailable();
    }

    public void persisted(String outcome, int recordCount) {
        increment("alphafrog.finance.record.batch", "outcome", safe(outcome));
        if (registry != null && recordCount > 0) {
            Counter.builder("alphafrog.finance.record.persisted")
                    .description("Persisted finance record audit rows")
                    .register(registry)
                    .increment(recordCount);
        }
    }

    public void crossEnvironment() {
        increment("alphafrog.finance.environment.check", "outcome", "cross_environment");
    }

    public void processingFailure(String category) {
        increment("alphafrog.finance.record.processor", "outcome", safe(category));
    }

    private void increment(String name, String tagName, String tagValue) {
        if (registry != null) {
            Counter.builder(name).tag(tagName, tagValue).register(registry).increment();
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.toLowerCase();
    }
}
