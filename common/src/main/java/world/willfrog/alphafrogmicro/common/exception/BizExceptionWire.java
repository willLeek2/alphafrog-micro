package world.willfrog.alphafrogmicro.common.exception;

import world.willfrog.alphafrogmicro.common.dto.ResponseCode;

/**
 * 把业务码编进远程调用异常文案。Triple 过网后只保留状态描述，读不出 {@link BizException} 字段。
 */
public final class BizExceptionWire {

    public static final String PREFIX = "AF_BIZ|";

    private BizExceptionWire() {
    }

    public static String encode(BizException biz) {
        String message = biz.getMessage() == null ? "" : biz.getMessage();
        return PREFIX + biz.getCode().getCode() + "|" + message;
    }

    public static BizException decode(Throwable error) {
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth < 16) {
            BizException decoded = decode(cursor.getMessage());
            if (decoded != null) {
                return decoded;
            }
            cursor = cursor.getCause();
            depth++;
        }
        return null;
    }

    public static BizException decode(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int idx = text.indexOf(PREFIX);
        if (idx < 0) {
            return null;
        }
        String rest = text.substring(idx + PREFIX.length());
        int newline = rest.indexOf('\n');
        if (newline >= 0) {
            rest = rest.substring(0, newline);
        }
        int bar = rest.indexOf('|');
        if (bar < 0) {
            return null;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(rest.substring(0, bar).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        ResponseCode code = ResponseCode.getByCode(parsed);
        if (code == ResponseCode.SYSTEM_ERROR && parsed != ResponseCode.SYSTEM_ERROR.getCode()) {
            return null;
        }
        return new BizException(code, rest.substring(bar + 1));
    }
}
