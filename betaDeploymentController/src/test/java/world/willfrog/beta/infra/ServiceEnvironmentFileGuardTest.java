package world.willfrog.beta.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.beta.core.ControllerException;

class ServiceEnvironmentFileGuardTest {
    @TempDir Path temporary;

    @Test
    void acceptsAnExistingRegularFileAndRejectsAWholeProductionDotenv() throws Exception {
        Path serviceFile = Files.writeString(temporary.resolve("agent-service.env"), "AF_DB_MAIN_HOST=beta-db\n");
        Path production = Files.writeString(temporary.resolve(".env"), "AF_DB_MAIN_PASSWORD=prod\n");
        ServiceEnvironmentFileGuard guard = new ServiceEnvironmentFileGuard();

        guard.requireDedicatedFile(serviceFile);
        guard.requireDedicatedFile(serviceFile);

        ControllerException wholeFile = assertThrows(ControllerException.class,
                () -> guard.requireDedicatedFile(production));
        assertEquals("ENV_FILE_WHOLE_PRODUCTION", wholeFile.code());
    }

    @Test
    void rejectsAMissingOrNonRegularEnvironmentFile() throws Exception {
        ServiceEnvironmentFileGuard guard = new ServiceEnvironmentFileGuard();

        ControllerException missing = assertThrows(ControllerException.class,
                () -> guard.requireDedicatedFile(null));
        assertEquals("SERVICE_CONFIG_MISSING", missing.code());

        ControllerException absent = assertThrows(ControllerException.class,
                () -> guard.requireDedicatedFile(temporary.resolve("missing.env")));
        assertEquals("SERVICE_CONFIG_INVALID", absent.code());

        Path directory = Files.createDirectory(temporary.resolve("env-dir"));
        ControllerException notFile = assertThrows(ControllerException.class,
                () -> guard.requireDedicatedFile(directory));
        assertEquals("SERVICE_CONFIG_INVALID", notFile.code());
    }

    @Test
    void rejectsAProductionDotenvDeclaredAsAVolume() {
        ServiceEnvironmentFileGuard guard = new ServiceEnvironmentFileGuard();

        guard.rejectProductionDotenvVolume("/srv/alphafrog/shared:/srv/alphafrog/shared:rw");
        ControllerException declared = assertThrows(ControllerException.class,
                () -> guard.rejectProductionDotenvVolume(temporary.resolve(".env") + ":/secret/.env:ro"));
        assertEquals("ENV_FILE_WHOLE_PRODUCTION", declared.code());
        assertTrue(ServiceEnvironmentFileGuard.isWholeProductionDotenv(Path.of("/repo/.env")));
    }
}
