package world.willfrog.beta.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import world.willfrog.beta.core.ControllerException;

/**
 * 核对每个服务使用自己的环境文件，并防止把生产环境整份 {@code .env}
 * 当作服务环境文件或数据卷挂进容器。
 */
final class ServiceEnvironmentFileGuard {
    void requireDedicatedFile(String serviceName, Path envFile, Map<String, Path> alreadyAssigned) {
        Path normalized = requireExistingSecretFile(envFile);
        if (isWholeProductionDotenv(normalized))
            throw new ControllerException("ENV_FILE_WHOLE_PRODUCTION",
                    "Service environment file must not be the whole production .env");
        for (Map.Entry<String, Path> assigned : alreadyAssigned.entrySet()) {
            if (!assigned.getKey().equals(serviceName) && assigned.getValue().equals(normalized))
                throw new ControllerException("ENV_FILE_SHARED",
                        "Each service must use its own environment file");
        }
    }

    void rejectProductionDotenvVolume(String volume) {
        if (volume == null || volume.isBlank()) return;
        String source = volume.split(":", 2)[0];
        if (source.isBlank()) return;
        if (isWholeProductionDotenv(Path.of(source)))
            throw new ControllerException("ENV_FILE_WHOLE_PRODUCTION",
                    "Service volumes must not mount the whole production .env");
    }

    static boolean isWholeProductionDotenv(Path path) {
        Path name = path.getFileName();
        return name != null && ".env".equalsIgnoreCase(name.toString());
    }

    private Path requireExistingSecretFile(Path envFile) {
        if (envFile == null)
            throw new ControllerException("SERVICE_CONFIG_MISSING", "Service environment file is not configured");
        Path normalized = envFile.toAbsolutePath().normalize();
        if (!normalized.isAbsolute() || Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized))
            throw new ControllerException("SERVICE_CONFIG_INVALID", "Service environment file is missing or unsafe");
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(normalized);
            if (permissions.stream().anyMatch(permission -> permission != PosixFilePermission.OWNER_READ
                    && permission != PosixFilePermission.OWNER_WRITE))
                throw new ControllerException("ENV_FILE_PERMISSIONS",
                        "Service environment file permissions are wider than 0600");
        } catch (UnsupportedOperationException ignored) {
        } catch (IOException exception) {
            throw new ControllerException("SERVICE_CONFIG_INVALID", "Unable to inspect the service environment file", exception);
        }
        return normalized;
    }

}
