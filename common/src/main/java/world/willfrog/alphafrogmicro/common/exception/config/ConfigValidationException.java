package world.willfrog.alphafrogmicro.common.exception.config;

/**
 * 配置推送前预检失败。未通过时不得写入 Nacos，也不该覆盖正在使用的版本。
 */
public class ConfigValidationException extends IllegalArgumentException {

    public ConfigValidationException(String message) {
        super(message);
    }
}
