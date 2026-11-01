package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceEnvironmentVerifierTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinanceEnvironmentVerifier verifier = new FinanceEnvironmentVerifier();




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
