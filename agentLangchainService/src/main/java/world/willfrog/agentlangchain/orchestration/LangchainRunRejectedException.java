package world.willfrog.agentlangchain.orchestration;

public class LangchainRunRejectedException extends RuntimeException {

    public LangchainRunRejectedException(String message) {
        super(message);
    }
}
