package world.willfrog.agentlangchain.deployment;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentIdentityStartupVerifierTest {

    @Test
    void beanInitializationFailsWhenTrustedIdentityCannotBeResolved() {
        DeploymentIdentityProvider provider = () -> {
            throw new IllegalArgumentException("deployment identity missing");
        };

        DeploymentIdentityStartupVerifier verifier =
                new DeploymentIdentityStartupVerifier(
                        provider,
                        new DeploymentRetirementAuthorizer(new MockEnvironment()
                                .withProperty(DeploymentRetirementAuthorizer.TOKEN_PROPERTY,
                                        "0123456789abcdef0123456789abcdef")));

        assertThrows(IllegalArgumentException.class, verifier::afterPropertiesSet);
    }

    @Test
    void beanInitializationFailsWhenRetirementCredentialIsMissing() {
        DeploymentIdentityProvider provider =
                () -> new world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity(
                        "stable", "gen-" + "a".repeat(64));
        DeploymentIdentityStartupVerifier verifier =
                new DeploymentIdentityStartupVerifier(
                        provider,
                        new DeploymentRetirementAuthorizer(new MockEnvironment()));

        assertThrows(IllegalStateException.class, verifier::afterPropertiesSet);
    }
}
