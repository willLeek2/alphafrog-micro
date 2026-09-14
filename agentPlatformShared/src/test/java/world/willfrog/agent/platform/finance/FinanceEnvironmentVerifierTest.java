package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceEnvironmentVerifierTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinanceEnvironmentVerifier verifier = new FinanceEnvironmentVerifier();

    @Test
    void matchingActualAndTargetRetainsDeclaredLibraryEvidence() throws Exception {
        FinanceEnvironmentFact actual = environment("sha256:runtime-a", "1.0");

        FinanceEnvironmentVerifier.Verification result = verifier.verify(
                objectMapper.readTree(record("sha256:runtime-a", "LIBRARY_CALL_DECLARED")),
                actual, environment("sha256:runtime-a", "1.0"));

        assertThat(result.effectiveEvidence()).isEqualTo(FinanceEvidenceLevel.LIBRARY_CALL_DECLARED);
        assertThat(result.reasons()).isEmpty();
        assertThat(result.crossEnvironment()).isFalse();
        assertThat(result.packageCompatible()).isTrue();
    }

    @Test
    void declaredActualAndTargetMismatchesDowngradeWithoutImposingRenderability() throws Exception {
        FinanceEnvironmentVerifier.Verification result = verifier.verify(
                objectMapper.readTree(record("sha256:declared", "LIBRARY_CALL_DECLARED")),
                environment("sha256:actual", "1.0"),
                environment("sha256:target", "1.0"));

        assertThat(result.effectiveEvidence()).isEqualTo(FinanceEvidenceLevel.CUSTOM_UNVERIFIED);
        assertThat(result.crossEnvironment()).isTrue();
        assertThat(result.reasons()).containsExactly(
                "DECLARED_ACTUAL_ENVIRONMENT_MISMATCH", "FINANCE_CROSS_ENVIRONMENT");
    }

    @Test
    void missingActualOrIncompatiblePackageApiDowngrades() throws Exception {
        assertThat(verifier.verify(
                objectMapper.readTree(record("sha256:declared", "CUSTOM_WITH_CHECKS")),
                null, null).reasons()).contains("ACTUAL_ENVIRONMENT_MISSING");

        FinanceEnvironmentVerifier.Verification incompatible = verifier.verify(
                objectMapper.readTree(record("sha256:actual", "LIBRARY_CALL_DECLARED")),
                environment("sha256:actual", "2.0"),
                environment("sha256:actual", "1.0"));
        assertThat(incompatible.packageCompatible()).isFalse();
        assertThat(incompatible.effectiveEvidence()).isEqualTo(FinanceEvidenceLevel.CUSTOM_UNVERIFIED);
        assertThat(incompatible.reasons()).contains("ACTUAL_PACKAGE_API_INCOMPATIBLE");
    }

    private static FinanceEnvironmentFact environment(String environmentId, String apiVersion) {
        return new FinanceEnvironmentFact(
                environmentId, "sha256:image", "sha256:library",
                List.of(new FinanceEnvironmentFact.PackageApi(
                        "alphafrog_finance", "1.0.3", apiVersion)), true);
    }

    private static String record(String environmentId, String evidence) {
        return "{\"environmentId\":\"" + environmentId + "\",\"evidence\":\"" + evidence + "\"}";
    }
}
