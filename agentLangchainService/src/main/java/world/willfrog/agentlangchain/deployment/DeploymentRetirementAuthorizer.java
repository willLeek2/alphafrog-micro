package world.willfrog.agentlangchain.deployment;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 校验部署控制器调用代际退役接口时使用的专用共享凭证。 */
@Component
public class DeploymentRetirementAuthorizer {

    public static final String TOKEN_PROPERTY = "AF_DEPLOYMENT_RETIREMENT_TOKEN";

    private final Environment environment;
    private volatile byte[] expectedToken;

    public DeploymentRetirementAuthorizer(Environment environment) {
        this.environment = environment;
    }

    public void verifyConfigured() {
        expectedToken();
    }

    public void authorize(String presentedToken) {
        byte[] presented = presentedToken == null
                ? new byte[0]
                : presentedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken(), presented)) {
            throw new SecurityException("deployment_retirement_unauthorized");
        }
    }

    private byte[] expectedToken() {
        byte[] current = expectedToken;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (expectedToken == null) {
                String configured = environment.getProperty(TOKEN_PROPERTY);
                if (configured == null || configured.length() < 32
                        || !configured.equals(configured.trim())
                        || configured.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalStateException(
                            TOKEN_PROPERTY + " 必须是不少于 32 个字符且不含空白边界或控制字符的专用凭证");
                }
                expectedToken = configured.getBytes(StandardCharsets.UTF_8);
            }
            return expectedToken;
        }
    }
}
