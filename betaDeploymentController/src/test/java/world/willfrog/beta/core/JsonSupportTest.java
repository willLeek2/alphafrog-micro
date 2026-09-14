package world.willfrog.beta.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JsonSupportTest {
    @Test
    void deploymentGenerationMatchesTheSharedCrossLanguageVector() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode vector = (ObjectNode) mapper.readTree(Files.readString(
                Path.of("../deploy/agent-run/deployment-generation-test-vector.json")));
        ObjectNode manifest = mapper.createObjectNode();
        manifest.set("manifestVersion", vector.path("manifestVersion"));
        manifest.set("gitCommit", vector.path("gitCommit"));
        var services = manifest.putArray("services");
        vector.path("serviceImages").fields().forEachRemaining(entry -> services.addObject()
                .put("serviceName", entry.getKey())
                .putObject("image").put("repositoryDigest", entry.getValue()));

        assertEquals(vector.path("deploymentGenerationId").asText(),
                JsonSupport.deploymentGeneration(manifest));
    }
}
