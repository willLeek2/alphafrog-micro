package world.willfrog.alphafrogmicro.common.exception;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提供方在业务异常过网前，把业务码写进远程调用异常文案，供前端入口还原。
 */
@Activate(group = CommonConstants.PROVIDER, order = 200)
public final class BizExceptionProviderFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(BizExceptionProviderFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) {
        Result result = invoker.invoke(invocation);
        if (!result.hasException()) {
            return result;
        }
        BizException biz = unwrap(result.getException());
        if (biz == null) {
            return result;
        }
        String encoded = BizExceptionWire.encode(biz);
        log.warn("BizException over Dubbo: method={}, wire={}", invocation.getMethodName(), encoded);
        result.setException(new RuntimeException(encoded));
        return result;
    }

    private static BizException unwrap(Throwable error) {
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth < 16) {
            if (cursor instanceof BizException biz) {
                return biz;
            }
            cursor = cursor.getCause();
            depth++;
        }
        return null;
    }
}
