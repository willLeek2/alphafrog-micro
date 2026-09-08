package world.willfrog.beta.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.core.ControllerException;
import world.willfrog.beta.core.JsonSupport;

class BetaContractValidatorTest {

    @Test
    void refusesACommonDeadlineThatLeavesNoNaturalProcessingWindow() {
        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setApplicationDrainSeconds(5);

        assertThrows(IllegalArgumentException.class,
                () -> new BetaContractValidator(new ObjectMapper(), properties));
    }

    @Test
    void acceptsDefaultDubboServiceGroupAndRequiresItsTrailingNacosColon() {
        ObjectMapper mapper = new ObjectMapper();
        BetaContractValidator validator = new BetaContractValidator(mapper, new BetaControllerProperties());
        ObjectNode manifest = manifest(mapper, "com.alphafrog.StockService",
                "providers:com.alphafrog.StockService::");

        assertDoesNotThrow(() -> validator.validateManifest(manifest));

        ((ObjectNode) manifest.path("services").path(0).path("registration"))
                .put("serviceName", "providers:com.alphafrog.StockService::default");
        ((ObjectNode) manifest.path("services").path(0)).put("serviceSpecSha256",
                JsonSupport.serviceSha256(mapper, manifest.path("services").path(0)));
        ControllerException failure = assertThrows(ControllerException.class,
                () -> validator.validateManifest(manifest));
        assertEquals("MANIFEST_INVALID", failure.code());
    }

    @Test
    void preservesExplicitDubboServiceGroupAndVersionMapping() {
        ObjectMapper mapper = new ObjectMapper();
        BetaContractValidator validator = new BetaContractValidator(mapper, new BetaControllerProperties());
        ObjectNode manifest = manifest(mapper, "langchain/com.alphafrog.AgentService:v2",
                "providers:com.alphafrog.AgentService:v2:langchain");

        assertDoesNotThrow(() -> validator.validateManifest(manifest));
    }

    private static ObjectNode manifest(ObjectMapper mapper, String serviceKey, String nacosServiceName) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("deploymentId", "beta-main-001");
        root.put("trafficScopeId", "main-beta");
        root.put("manifestVersion", 1);
        root.put("gitCommit", "1".repeat(40));
        root.putObject("owner").put("ownerId", "frog");
        root.put("createdAt", "2026-09-01T00:00:00Z");
        root.put("expiresAt", "2026-09-08T00:00:00Z");
        ObjectNode service = root.putArray("services").addObject();
        service.put("serviceName", "stock-service");
        service.put("dubboServiceKey", serviceKey);
        service.put("releaseId", "release-1");
        service.put("serviceSpecSha256", "0".repeat(64));
        service.put("machineId", "beta-machine-1");
        service.putObject("image")
                .put("repositoryDigest", "stock-service:local")
                .put("localImageId", "sha256:" + "a".repeat(64));
        ObjectNode runtime = service.putObject("runtime");
        runtime.put("containerPort", 50055);
        runtime.putArray("hostPorts").add(51055).add(52055);
        runtime.put("healthCheckProfile", "CONTROLLER_TCP_V1");
        runtime.put("readinessTimeoutSeconds", 120);
        runtime.put("shutdownProfile", "SPRING_BOOT_DUBBO_V1");
        runtime.put("applicationDrainSeconds", 60);
        runtime.put("drainGraceSeconds", 60);
        ObjectNode registration = service.putObject("registration");
        registration.put("serviceName", nacosServiceName);
        registration.put("groupName", "alphafrog-beta");
        registration.put("namespaceId", "public");
        registration.put("clusterName", "DEFAULT");
        registration.put("applicationName", "stock-service");
        service.putNull("runtimeConfigSha256");
        service.put("serviceSpecSha256", JsonSupport.serviceSha256(mapper, service));
        return root;
    }
}
