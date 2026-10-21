package world.willfrog.alphafrogmicro.frontend.rpc;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.rpc.RpcException;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.common.exception.BizException;
import world.willfrog.alphafrogmicro.common.exception.BizExceptionWire;

/**
 * 把组合、策略远程调用失败映射成前端入口响应体业务码。
 *
 * <p>判定顺序：原因链找业务异常类型；文案解码业务码；其余远程调用异常记 520；入口本地未捕获异常记 500。</p>
 */
@Slf4j
public final class FrontendRpcFailureMapper {

    private FrontendRpcFailureMapper() {
    }

    public static <T> ResponseWrapper<T> toWrapper(Throwable error, String action) {
        BizException biz = findBizException(error);
        if (biz == null) {
            biz = BizExceptionWire.decode(error);
        }
        if (biz != null) {
            log.warn("{}业务失败: code={}, message={}", action, biz.getCode().getCode(), biz.getMessage());
            return ResponseWrapper.error(biz.getCode(), biz.getMessage());
        }
        if (findRpcException(error) != null) {
            log.error("{}失败: class={}, message={}", action, error.getClass().getName(), error.getMessage());
            return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, action + "失败");
        }
        log.error("{}失败", action, error);
        return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, action + "失败");
    }

    static BizException findBizException(Throwable error) {
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

    private static RpcException findRpcException(Throwable error) {
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth < 16) {
            if (cursor instanceof RpcException rpc) {
                return rpc;
            }
            cursor = cursor.getCause();
            depth++;
        }
        return null;
    }
}
