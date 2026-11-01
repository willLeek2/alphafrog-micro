package world.willfrog.agent.tools.python;

import static world.willfrog.agent.tools.support.ToolPromptTestFixture.configure;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.PythonRiskReplayEvidenceMissingException;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.PythonRiskReviewService;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import com.google.protobuf.util.JsonFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PythonRiskPreflightTest {
    @AfterEach
    void clearContext() {
        AgentContext.clearToolCallId();
    }






    private PythonSandboxTools toolsWith(PythonRiskReviewService review) {
        PythonSandboxTools tools = configure(new PythonSandboxTools(new ObjectMapper()));
        ReflectionTestUtils.setField(tools, "pythonRiskReviewService", review);
        return tools;
    }
}
