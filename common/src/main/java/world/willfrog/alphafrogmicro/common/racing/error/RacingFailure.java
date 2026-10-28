package world.willfrog.alphafrogmicro.common.racing.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import world.willfrog.racing.idl.RacingError;

/** 保留平台拒绝的稳定原因与对象编号，不携带远端自由文字、正文、凭据或异常。 */
public record RacingFailure(String code, String reasonCode, Map<String, String> objectLocator) {
    private static final Set<String> LOCATOR_KEYS = Set.of(
            "environment", "scope", "scopeid", "scopeinstanceid", "targetscopeinstanceid",
            "ownerscopeinstanceid", "ownernamespace", "ownerkind", "rootid", "currentrunid", "runid",
            "operationid", "promptid", "version", "promptversion", "promptsetid", "promptsetversion",
            "selectionid", "selectionkey", "experimentid", "configurationversion", "candidateid",
            "objectid", "objecttype", "resourcetype", "field");

    public RacingFailure {
        code = stableCode(code);
        reasonCode = stableCode(reasonCode);
        Map<String, String> safe = new LinkedHashMap<>();
        if (objectLocator != null) {
            objectLocator.forEach((key, value) -> {
                String normalized = key == null ? "" : key.replace("_", "").toLowerCase(Locale.ROOT);
                if (key != null && key.length() <= 64 && LOCATOR_KEYS.contains(normalized)
                        && value != null && value.length() <= 256
                        && (value.matches("[A-Za-z0-9_.:\\[\\]-]+")
                            || (normalized.equals("field") && value.startsWith("/")
                                && value.matches("/[A-Za-z0-9_./~\\[\\]-]+")))) {
                    safe.put(key, value);
                }
            });
        }
        objectLocator = Collections.unmodifiableMap(safe);
    }

    public static RacingFailure none() {
        return new RacingFailure("", "", Map.of());
    }

    public static RacingFailure from(RacingError error) {
        if (error == null) return none();
        String reason = error.getDetailsMap().getOrDefault("reason_code",
                error.getDetailsMap().getOrDefault("reasonCode", ""));
        return new RacingFailure(error.getCode(), reason, error.getObjectLocatorMap());
    }

    public boolean isEmpty() {
        return code.isEmpty() && reasonCode.isEmpty() && objectLocator.isEmpty();
    }

    private static String stableCode(String value) {
        return value != null && value.length() <= 80 && value.matches("[A-Z][A-Z0-9_]*") ? value : "";
    }
}
