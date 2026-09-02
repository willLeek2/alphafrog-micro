package world.willfrog.beta.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.core.ControllerException;

class AtomicJsonStoreTest {
    @TempDir Path temporary;

    @Test
    void stateReplacementIncrementsVersionAndPersistsCompleteJson() {
        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setStateRoot(temporary.resolve("state"));
        AtomicJsonStore store = new AtomicJsonStore(new ObjectMapper(), properties);
        store.update(state -> { state.put("marker", "one"); return null; });
        store.update(state -> { state.put("marker", "two"); return null; });
        assertEquals(2, store.snapshot().path("stateVersion").asLong());
        assertEquals("two", store.snapshot().path("marker").asText());
    }

    @Test
    void rejectsTraversalAndManifestSymlink() throws Exception {
        BetaControllerProperties properties = new BetaControllerProperties();
        properties.setStateRoot(temporary.resolve("state"));
        AtomicJsonStore store = new AtomicJsonStore(new ObjectMapper(), properties);
        assertEquals("DEPLOYMENT_ID_INVALID", assertThrows(ControllerException.class,
                () -> store.readManifest("../escape")).code());
        Path deployment = temporary.resolve("state/deployments/beta-main-001");
        Files.createDirectories(deployment);
        Path target = temporary.resolve("elsewhere.json");
        Files.writeString(target, "{}");
        Files.createSymbolicLink(deployment.resolve("manifest.json"), target);
        assertEquals("SYMLINK_REJECTED", assertThrows(ControllerException.class,
                () -> store.readManifest("beta-main-001")).code());
    }
}
