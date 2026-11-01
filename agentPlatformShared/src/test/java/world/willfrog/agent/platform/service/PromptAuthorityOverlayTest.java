package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.prompt.PromptRunSelection;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 运行时覆盖叠加权威默认版本的行为：生效切换、加载校验拒绝、回落与指纹留存。 */
class PromptAuthorityOverlayTest {

    private final PromptAuthority authority = PromptAuthority.shared();

    @AfterEach
    void cleanUp() {
        authority.clearOverlay();
    }










}
