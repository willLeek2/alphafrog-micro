package world.willfrog.agent.platform.capacity;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 三层名额的一次读数。
 *
 * <p>这是「系统现在担了多少活」的统一回答：业务准入答系统担了多少活，协调许可答同时在推进多少张图，
 * 节点许可答同时在跑多少个分段。三层分开报，不合并成一个数。</p>
 */
public record SchedulerPermitSnapshot(Map<SchedulerPermitLayer, PermitUsage> usages, OffsetDateTime takenAt) {

    public SchedulerPermitSnapshot {
        usages = Map.copyOf(usages);
    }

    public static SchedulerPermitSnapshot of(List<PermitUsage> usages) {
        Map<SchedulerPermitLayer, PermitUsage> map = new EnumMap<>(SchedulerPermitLayer.class);
        for (PermitUsage usage : usages) {
            map.put(usage.layer(), usage);
        }
        return new SchedulerPermitSnapshot(map, OffsetDateTime.now());
    }

    public PermitUsage usage(SchedulerPermitLayer layer) {
        PermitUsage usage = usages.get(layer);
        if (usage == null) {
            throw new IllegalStateException("这一层名额没有读数：" + layer);
        }
        return usage;
    }

    /** 一行日志，便于把三层名额随时间的取值写进验收证据。 */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        for (SchedulerPermitLayer layer : SchedulerPermitLayer.values()) {
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(usage(layer).describe());
        }
        return builder.toString();
    }
}
