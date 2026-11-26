package world.willfrog.alphafrogmicro.common.racing.input;

import java.util.Objects;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

/** 输入上下文的嵌套作用域；跨线程时显式捕获current并在目标线程重新open。 */
public final class ModelInputScope implements AutoCloseable {
    private static final ThreadLocal<ModelInputScope> CURRENT = new ThreadLocal<>();
    private final ModelInputScope previous;
    private final ModelInputInvocation installed;
    private final Thread owner;
    private boolean closed;

    private ModelInputScope(ModelInputInvocation invocation) {
        installed = Objects.requireNonNull(invocation, "invocation");
        previous = CURRENT.get();
        owner = Thread.currentThread();
        CURRENT.set(this);
    }

    public static ModelInputScope open(ModelInputInvocation invocation) {
        return new ModelInputScope(invocation);
    }

    public static ModelInputInvocation currentRequired() {
        ModelInputInvocation invocation = current();
        if (invocation == null) {
            throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                    "本次模型请求尚未建立输入与版本来源上下文");
        }
        return invocation;
    }

    public static ModelInputInvocation current() {
        ModelInputScope scope = CURRENT.get();
        return scope == null ? null : scope.installed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (owner != Thread.currentThread() || CURRENT.get() != this) {
            throw new IllegalStateException("模型输入作用域必须在原线程按嵌套顺序关闭");
        }
        closed = true;
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
