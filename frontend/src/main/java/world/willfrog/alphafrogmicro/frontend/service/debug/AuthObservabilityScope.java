package world.willfrog.alphafrogmicro.frontend.service.debug;

import lombok.Data;

import java.util.List;
import java.util.regex.Pattern;

@Data
public class AuthObservabilityScope {

    private List<String> sampleUsers;
    private String usernamePattern;
    private List<String> pathIncludes;

    private static final List<String> BROADNESS_PROBE_USERNAMES = List.of(
            "alice", "bob123", "user_a", "x", "stress_test_1");

    public boolean isEmpty() {
        return (sampleUsers == null || sampleUsers.isEmpty())
                && (usernamePattern == null || usernamePattern.isBlank())
                && (pathIncludes == null || pathIncludes.isEmpty());
    }

    public boolean isEffectivelyAllUsers() {
        if (isEmpty()) {
            return true;
        }
        if (pathIncludes != null) {
            for (String path : pathIncludes) {
                if (path == null || path.isBlank() || "/".equals(path)) {
                    return true;
                }
            }
        }
        if (usernamePattern != null && !usernamePattern.isBlank()) {
            try {
                Pattern pattern = Pattern.compile(usernamePattern);
                for (String probe : BROADNESS_PROBE_USERNAMES) {
                    if (!pattern.matcher(probe).find()) {
                        return false;
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
