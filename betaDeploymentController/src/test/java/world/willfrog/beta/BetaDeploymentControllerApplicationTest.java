package world.willfrog.beta;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import world.willfrog.beta.config.BetaControllerProperties;

@SpringBootTest(properties = "alphafrog.beta-controller.enabled=false")
class BetaDeploymentControllerApplicationTest {
    @Autowired private BetaControllerProperties properties;
    @Autowired private ObjectMapper mapper;

    @Test
    void controllerRemainsDisabledUnlessItIsExplicitlyEnabled() {
        assertFalse(properties.isEnabled());
    }

    @Test
    void runtimeJsonRejectsDuplicateObjectKeys() {
        assertThrows(JsonProcessingException.class, () -> mapper.readTree("{\"stateVersion\":1,\"stateVersion\":2}"));
    }
}
