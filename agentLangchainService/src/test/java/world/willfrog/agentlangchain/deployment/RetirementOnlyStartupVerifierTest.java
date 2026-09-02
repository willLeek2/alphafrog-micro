package world.willfrog.agentlangchain.deployment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetirementOnlyStartupVerifierTest {

    @Test
    void rejectsARepairInstanceThatWouldRegisterInServiceDiscovery() {
        RetirementOnlyStartupVerifier verifier = new RetirementOnlyStartupVerifier(true);

        assertThatThrownBy(verifier::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AF_DUBBO_REGISTRY_REGISTER=false");
    }

    @Test
    void acceptsARepairInstanceHiddenFromServiceDiscovery() {
        RetirementOnlyStartupVerifier verifier = new RetirementOnlyStartupVerifier(false);

        assertThatCode(verifier::afterPropertiesSet).doesNotThrowAnyException();
    }
}
