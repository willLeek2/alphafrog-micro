package world.willfrog.agent.tools.dataanalysis;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 读取运行创建时写入的管理员标记。标记在创建当时确定，查询时不再回读账号状态。
 */
public final class MarketDbRunAccess {

    private MarketDbRunAccess() {
    }

    public static boolean isAdminSnapshot(String extJson, ObjectMapper objectMapper) {
        if (extJson == null || extJson.isBlank() || objectMapper == null) {
            return false;
        }
        try {
            Map<?, ?> ext = objectMapper.readValue(extJson, Map.class);
            Object flag = ext.get("is_admin");
            if (flag == null) {
                flag = ext.get("isAdmin");
            }
            return truthy(flag);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }
}
