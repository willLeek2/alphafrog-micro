package world.willfrog.beta.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.beta.core.ControllerException;

class ServiceEnvironmentFileGuardTest {
    @TempDir Path temporary;

    @Test
    void acceptsADedicatedOwnerOnlyFileAndRejectsProductionDotenvSharingAndWorldReadableFiles() throws Exception {
        Path serviceFile = writeSecret(temporary.resolve("agent-service.env"), "AF_DB_MAIN_HOST=beta-db\n");
        Path otherFile = writeSecret(temporary.resolve("frontend.env"), "AF_DB_MAIN_HOST=beta-db\n");
        Path production = writeSecret(temporary.resolve(".env"), "AF_DB_MAIN_PASSWORD=prod\n");
        ServiceEnvironmentFileGuard guard = new ServiceEnvironmentFileGuard();
        Map<String, Path> assigned = new LinkedHashMap<>();

        guard.requireDedicatedFile("agent-service", serviceFile, assigned);
        assigned.put("agent-service", serviceFile.toAbsolutePath().normalize());
        guard.requireDedicatedFile("frontend", otherFile, assigned);

        ControllerException wholeFile = assertThrows(ControllerException.class,
                () -> guard.requireDedicatedFile("agent-service", production, Map.of()));
        assertEquals("ENV_FILE_WHOLE_PRODUCTION", wholeFile.code());

        ControllerException shared = assertThrows(ControllerException.class,
                () -> guard.requireDedicatedFile("frontend", serviceFile, assigned));
        assertEquals("ENV_FILE_SHARED", shared.code());

        try { Files.setPosixFilePermissions(serviceFile, PosixFilePermissions.fromString("rw-r--r--")); }
        catch (UnsupportedOperationException ignored) { return; }
        ControllerException readable = assertThrows(ControllerException.class,
                () -> guard.requireDedicatedFile("agent-service", serviceFile, Map.of()));
        assertEquals("ENV_FILE_PERMISSIONS", readable.code());
    }

    @Test
    void rejectsEffectiveComposeThatAttachesOrMountsProductionDotenv() throws Exception {
        Path serviceFile = writeSecret(temporary.resolve("agent-service.env"), "SERVER_PORT=18080\n");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode app = mapper.createObjectNode();
        app.putArray("env_file").add(serviceFile.toAbsolutePath().normalize().toString());
        ServiceEnvironmentFileGuard guard = new ServiceEnvironmentFileGuard();
        guard.requireEffectiveCompose(app, serviceFile);

        app.putArray("env_file").add(temporary.resolve(".env").toAbsolutePath().normalize().toString());
        ControllerException extraFile = assertThrows(ControllerException.class,
                () -> guard.requireEffectiveCompose(app, serviceFile));
        assertEquals("ENV_FILE_MISMATCH", extraFile.code());

        ObjectNode leaked = mapper.createObjectNode();
        leaked.putArray("env_file").add(temporary.resolve(".env").toString());
        ControllerException dotenv = assertThrows(ControllerException.class,
                () -> guard.requireEffectiveCompose(leaked, temporary.resolve(".env")));
        assertEquals("ENV_FILE_WHOLE_PRODUCTION", dotenv.code());

        ObjectNode mounted = mapper.createObjectNode();
        mounted.putArray("env_file").add(serviceFile.toString());
        mounted.putArray("volumes").add(temporary.resolve(".env") + ":/app/.env:ro");
        ControllerException volume = assertThrows(ControllerException.class,
                () -> guard.requireEffectiveCompose(mounted, serviceFile));
        assertEquals("ENV_FILE_WHOLE_PRODUCTION", volume.code());

        guard.rejectProductionDotenvVolume("/srv/alphafrog/shared:/srv/alphafrog/shared:rw");
        ControllerException declared = assertThrows(ControllerException.class,
                () -> guard.rejectProductionDotenvVolume(temporary.resolve(".env") + ":/secret/.env:ro"));
        assertEquals("ENV_FILE_WHOLE_PRODUCTION", declared.code());
        assertTrue(ServiceEnvironmentFileGuard.isWholeProductionDotenv(Path.of("/repo/.env")));
    }

    @Test
    void acceptsComposeObjectEnvFileEntries() throws Exception {
        Path serviceFile = writeSecret(temporary.resolve("agent-service.env"), "SERVER_PORT=18080\n");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode app = mapper.createObjectNode();
        ArrayNode files = app.putArray("env_file");
        ObjectNode entry = files.addObject();
        entry.put("path", serviceFile.toAbsolutePath().normalize().toString());
        entry.put("required", true);
        new ServiceEnvironmentFileGuard().requireEffectiveCompose(app, serviceFile);
    }

    private Path writeSecret(Path path, String content) throws Exception {
        Files.writeString(path, content);
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        return path;
    }
}
