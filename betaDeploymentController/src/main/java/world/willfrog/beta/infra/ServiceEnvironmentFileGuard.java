package world.willfrog.beta.infra;

import java.nio.file.Files;
import java.nio.file.Path;
import world.willfrog.beta.core.ControllerException;

/**
 * 核对服务环境文件已经配置且是普通文件，并防止把生产环境整份 {@code .env}
 * 当作服务环境文件或数据卷挂进容器。
 */
final class ServiceEnvironmentFileGuard {
    void requireDedicatedFile(Path envFile) {
        Path normalized = requireExistingRegularFile(envFile);
        if (isWholeProductionDotenv(normalized))
            throw new ControllerException("ENV_FILE_WHOLE_PRODUCTION",
                    "Service environment file must not be the whole production .env");
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

    private Path requireExistingRegularFile(Path envFile) {
        if (envFile == null)
            throw new ControllerException("SERVICE_CONFIG_MISSING", "Service environment file is not configured");
        Path normalized = envFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized))
            throw new ControllerException("SERVICE_CONFIG_INVALID",
                    "Service environment file is missing or not a regular file");
        return normalized;
    }
}
