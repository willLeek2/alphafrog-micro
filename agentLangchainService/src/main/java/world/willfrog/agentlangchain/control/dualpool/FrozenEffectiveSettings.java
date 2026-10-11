package world.willfrog.agentlangchain.control.dualpool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只能在启动时生效的那些参数，真正在用的值。
 *
 * <p>线程池、许可台账、提示队列、定时任务与租约这些组件在构造时会把读到的值归一化（至少 1、
 * 核心线程数不超过上限、名字为空时给个默认），此后一直用归一化之后的那份字段。健康读数要是重新去
 * 读属性源，看到的是「请求值」而不是「在用的值」：{@code core=8, max=2} 会显示成 8/2，而线程池其实
 * 是 2/2；属性源在启动之后又变过时，两份值更是互不相干。</p>
 *
 * <p>所以这些组件在构造时把自己字段里的值登记进来，这里只保存、不做任何换算。同一个参数被多个组件
 * 读、各自的归一化又不完全一样时（例如租约时长），保留每个组件报的值，读数里并排列出，不替它们挑一个
 * 看上去更整齐的。</p>
 */
@Component
public class FrozenEffectiveSettings {

    private final Map<String, Map<String, Object>> inUseByKey = new ConcurrentHashMap<>();

    /**
     * 登记一个启动时定死的参数此刻在用的值。
     *
     * @param key       属性名，与 {@code @Value} 里写的必须是同一个
     * @param component 谁在用（类名或组件名），同一个参数有多个消费者时用它区分
     * @param value     这个组件字段里的值：归一化之后、此后不会再变的那个
     */
    public void register(String key, String component, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("登记键不能为空");
        }
        if (component == null || component.isBlank()) {
            throw new IllegalArgumentException("登记必须说明是谁在用：" + key);
        }
        if (value == null) {
            throw new IllegalArgumentException("登记的值不能为空：" + key);
        }
        inUseByKey.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(component, value);
    }

    /** 这个参数此刻各个消费者在用的值；没有任何组件登记过时返回空表。 */
    public Map<String, Object> inUseBy(String key) {
        Map<String, Object> values = inUseByKey.get(key);
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(values));
    }

    /** 全部登记过的参数与各自在用的值，给读数与排查用。 */
    public Map<String, Map<String, Object>> all() {
        Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>();
        inUseByKey.keySet().stream().sorted().forEach(key -> snapshot.put(key, inUseBy(key)));
        return Map.copyOf(snapshot);
    }
}
