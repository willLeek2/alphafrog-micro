package world.willfrog.agentlangchain.deployment;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentRetirementAuthorizerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsOnlyTheConfiguredControllerCredential() {
        DeploymentRetirementAuthorizer authorizer = authorizer(TOKEN);

        assertThatCode(() -> authorizer.authorize(TOKEN)).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.authorize("0123456789abcdef0123456789abcdeg"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("deployment_retirement_unauthorized");
        assertThatThrownBy(() -> authorizer.authorize(null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsMissingShortOrControlCharacterCredentials() {
        assertThatThrownBy(() -> authorizer(null).verifyConfigured())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> authorizer("too-short").verifyConfigured())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> authorizer(TOKEN + "\n").verifyConfigured())
                .isInstanceOf(IllegalStateException.class);
    }

    private static DeploymentRetirementAuthorizer authorizer(String token) {
        MockEnvironment environment = new MockEnvironment();
        if (token != null) {
            environment.withProperty(DeploymentRetirementAuthorizer.TOKEN_PROPERTY, token);
        }
        return new DeploymentRetirementAuthorizer(environment);
    }
}
