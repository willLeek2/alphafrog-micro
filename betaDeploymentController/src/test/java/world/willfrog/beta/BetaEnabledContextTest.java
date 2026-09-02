package world.willfrog.beta;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import world.willfrog.beta.core.BetaDeploymentService;
import world.willfrog.beta.core.ContainerRuntime;
import world.willfrog.beta.core.RetirementGateway;
import world.willfrog.beta.core.ServiceRegistry;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "alphafrog.beta-controller.enabled=true")
class BetaEnabledContextTest {
    private static final Path ROOT = createRuntimeDirectory();
    private static final Path API_TOKEN = secret("api-token");
    private static final Path RETIREMENT_TOKEN = secret("retirement-token");

    @MockBean ContainerRuntime containers;
    @MockBean ServiceRegistry registry;
    @MockBean RetirementGateway retirement;
    @Autowired BetaDeploymentService service;

    @DynamicPropertySource
    static void runtimeProperties(DynamicPropertyRegistry properties) {
        properties.add("alphafrog.beta-controller.state-root", () -> ROOT.resolve("state"));
        properties.add("alphafrog.beta-controller.api-token-file", () -> API_TOKEN);
        properties.add("alphafrog.beta-controller.retirement-token-file", () -> RETIREMENT_TOKEN);
    }

    @Test
    void enabledControllerBuildsItsRuntimeGraphWithSafeEmptyState() {
        assertNotNull(service);
        assertNotNull(service.reconcileOne());
    }

    private static Path createRuntimeDirectory() {
        try { return Files.createTempDirectory("beta-enabled-context-"); }
        catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    private static Path secret(String name) {
        try {
            Path file = ROOT.resolve(name);
            Files.writeString(file, "s".repeat(48));
            try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            return file;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
