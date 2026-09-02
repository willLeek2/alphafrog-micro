package world.willfrog.beta.core;

public interface RetirementGateway {
    void retire(String address, int port, String deploymentId, String generationId, String token);
}
