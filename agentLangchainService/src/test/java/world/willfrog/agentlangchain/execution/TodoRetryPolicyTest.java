package world.willfrog.agentlangchain.execution;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TodoRetryPolicyTest {



    private TodoToolExecutionException failure(String name, String arguments, String message) {
        return new TodoToolExecutionException(new IllegalStateException(message),
                ToolExecutionRequest.builder().id("call-1").name(name).arguments(arguments).build());
    }
}
