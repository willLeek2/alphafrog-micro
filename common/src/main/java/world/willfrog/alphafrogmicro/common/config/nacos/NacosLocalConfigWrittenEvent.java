package world.willfrog.alphafrogmicro.common.config.nacos;

import org.springframework.context.ApplicationEvent;

/**
 * Nacos 已经把某条订阅的生效内容写进本地缓存文件。
 *
 * <p>本地文件只是这份内容的磁盘缓存。加载器收到事件后必须立刻重读该文件，
 * 不能继续使用启动时挂载目录里那份种子，也不能再等下一轮定时轮询。</p>
 */
public class NacosLocalConfigWrittenEvent extends ApplicationEvent {

    private final String targetFile;
    private final String dataId;
    private final String effectiveDataId;
    private final String source;

    public NacosLocalConfigWrittenEvent(Object sourceBean, String targetFile, String dataId,
                                        String effectiveDataId, String source) {
        super(sourceBean);
        this.targetFile = targetFile;
        this.dataId = dataId;
        this.effectiveDataId = effectiveDataId;
        this.source = source;
    }

    public String getTargetFile() {
        return targetFile;
    }

    public String getDataId() {
        return dataId;
    }

    public String getEffectiveDataId() {
        return effectiveDataId;
    }

    public String getWriteSource() {
        return source;
    }
}
