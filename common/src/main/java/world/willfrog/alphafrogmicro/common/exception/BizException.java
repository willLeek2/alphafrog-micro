package world.willfrog.alphafrogmicro.common.exception;

import lombok.Getter;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;

/**
 * 业务失败。携带稳定业务码，供直连 HTTP 与前端入口远程调用共用。
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ResponseCode code;

    public BizException(ResponseCode code, String message) {
        super(message);
        this.code = code;
    }
}
