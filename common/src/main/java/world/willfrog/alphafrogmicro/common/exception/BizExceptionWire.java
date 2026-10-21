package world.willfrog.alphafrogmicro.common.exception;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;

/**
 * 把业务码编进远程调用异常文案。Triple 过网后只保留状态描述，读不出 {@link BizException} 字段。
 *
 * <p>过网通道只承载 ASCII。泳道验收实测中文被替换成问号，因此说明段用 UTF-8 字节做不带填充的
 * base64url 装甲后再写入文案。</p>
 */
public final class BizExceptionWire {

    public static final String PREFIX = "AF_BIZ|";

    private static final Base64.Encoder MESSAGE_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder MESSAGE_DECODER = Base64.getUrlDecoder();

    private BizExceptionWire() {
    }

    public static String encode(BizException biz) {
        String message = biz.getMessage() == null ? "" : biz.getMessage();
        String armored = MESSAGE_ENCODER.encodeToString(message.getBytes(StandardCharsets.UTF_8));
        return PREFIX + biz.getCode().getCode() + "|" + armored;
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
        String message = decodeMessage(rest.substring(bar + 1).trim());
        if (message == null) {
            return null;
        }
        return new BizException(code, message);
    }

    private static String decodeMessage(String armored) {
        try {
            byte[] bytes = MESSAGE_DECODER.decode(armored);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
