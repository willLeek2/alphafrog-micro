package world.willfrog.beta.validation;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.beta.config.BetaControllerProperties;

class BetaContractValidatorTest {

    @Test
    void refusesACommonDeadlineThatLeavesNoNaturalProcessingWindow() {
        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setApplicationDrainSeconds(5);

        assertThrows(IllegalArgumentException.class,
                () -> new BetaContractValidator(new ObjectMapper(), properties));
    }
}
