package world.willfrog.agent.tools.market.advanced;

import lombok.Getter;

@Getter
public class AdvancedSearchException extends RuntimeException {

    private final String code;

    public AdvancedSearchException(String code, String message) {
        super(message);
        this.code = code;
    }
}
