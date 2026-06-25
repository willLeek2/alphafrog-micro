package world.willfrog.alphafrogmicro.frontend.service.debug;

import lombok.Data;

import java.util.List;

@Data
public class AuthObservabilityScope {

    private List<String> sampleUsers;
    private String usernamePattern;
    private List<String> pathIncludes;

    public boolean isEmpty() {
        return (sampleUsers == null || sampleUsers.isEmpty())
                && (usernamePattern == null || usernamePattern.isBlank())
                && (pathIncludes == null || pathIncludes.isEmpty());
    }
}
