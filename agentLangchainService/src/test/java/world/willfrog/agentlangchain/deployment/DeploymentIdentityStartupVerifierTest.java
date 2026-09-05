package world.willfrog.agentlangchain.deployment;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentIdentityStartupVerifierTest {

    @Test
    void beanInitializationFailsWhenTrustedIdentityCannotBeResolved() {
        DeploymentIdentityProvider provider = () -> {
            throw new IllegalArgumentException("deployment identity missing");
        };

        DeploymentIdentityStartupVerifier verifier =
                new DeploymentIdentityStartupVerifier(provider);

        assertThrows(IllegalArgumentException.class, verifier::afterPropertiesSet);
    }

}
