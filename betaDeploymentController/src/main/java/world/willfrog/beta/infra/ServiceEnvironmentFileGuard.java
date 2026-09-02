package world.willfrog.beta.infra;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import world.willfrog.beta.core.ControllerException;

/**
 * 核对每个服务使用自己的环境文件，并且有效 Compose 不会把生产环境整份 {@code .env}
 * 作为 {@code env_file} 或数据卷挂进容器。
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

    void requireEffectiveCompose(JsonNode app, Path expectedEnvFile) {
        Path expected = expectedEnvFile.toAbsolutePath().normalize();
        List<Path> actual = envFiles(app.path("env_file"));
        if (actual.size() != 1 || !expected.equals(actual.get(0)))
            throw new ControllerException("ENV_FILE_MISMATCH",
                    "Effective Compose env_file must be exactly the dedicated service environment file");
        if (isWholeProductionDotenv(actual.get(0)))
            throw new ControllerException("ENV_FILE_WHOLE_PRODUCTION",
                    "Effective Compose must not attach the whole production .env");
        for (JsonNode volume : app.path("volumes")) {
            String source = volumeSource(volume);
            if (source != null && isWholeProductionDotenv(Path.of(source)))
                throw new ControllerException("ENV_FILE_WHOLE_PRODUCTION",
                        "Effective Compose must not mount the whole production .env");
        }
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

    private List<Path> envFiles(JsonNode node) {
        List<Path> files = new ArrayList<>();
        if (node.isMissingNode() || node.isNull()) return files;
        if (node.isTextual()) {
            files.add(Path.of(node.asText()).toAbsolutePath().normalize());
            return files;
        }
        if (!node.isArray())
            throw new ControllerException("ENV_FILE_MISMATCH", "Effective Compose env_file is not a list");
        for (JsonNode item : node) {
            if (item.isTextual()) files.add(Path.of(item.asText()).toAbsolutePath().normalize());
            else if (item.isObject() && item.path("path").isTextual())
                files.add(Path.of(item.path("path").asText()).toAbsolutePath().normalize());
            else throw new ControllerException("ENV_FILE_MISMATCH", "Effective Compose env_file entry is invalid");
        }
        return files;
    }

    private String volumeSource(JsonNode volume) {
        if (volume.isTextual()) {
            String value = volume.asText();
            int separator = value.indexOf(':');
            return separator < 0 ? value : value.substring(0, separator);
        }
        if (volume.isObject() && volume.path("source").isTextual()) return volume.path("source").asText();
        return null;
    }
}
