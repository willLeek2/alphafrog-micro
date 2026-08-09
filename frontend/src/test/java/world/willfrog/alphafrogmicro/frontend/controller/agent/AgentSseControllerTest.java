package world.willfrog.alphafrogmicro.frontend.controller.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.AgentSseService;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentAuthSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSseControllerTest {

    @Test
    void stream_shouldPreferAfterSeqOverLastEventId() {
        AgentSseService sseService = mock(AgentSseService.class);
        AuthService authService = mock(AuthService.class);
        AgentSseController controller = new AgentSseController(sseService, new AgentAuthSupport(authService));
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("u1");
        User user = new User();
        user.setUserId(1127L);
        when(authService.getUserByUsername("u1")).thenReturn(user);

        controller.stream(authentication, "run-1", 7, "42");

        ArgumentCaptor<SseEmitter> emitterCaptor = ArgumentCaptor.forClass(SseEmitter.class);
        verify(sseService).connect(eq("run-1"), eq("1127"), eq(7), emitterCaptor.capture());
    }

    @Test
    void stream_shouldUseLastEventIdWhenAfterSeqMissing() {
        AgentSseService sseService = mock(AgentSseService.class);
        AuthService authService = mock(AuthService.class);
        AgentSseController controller = new AgentSseController(sseService, new AgentAuthSupport(authService));
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("u1");
        User user = new User();
        user.setUserId(1127L);
        when(authService.getUserByUsername("u1")).thenReturn(user);

        controller.stream(authentication, "run-1", null, "42");

        verify(sseService).connect(eq("run-1"), eq("1127"), eq(42), any(SseEmitter.class));
    }

    @Test
    void resolveResumeAfterSeq_shouldIgnoreInvalidLastEventId() {
        AgentSseController controller = new AgentSseController(mock(AgentSseService.class),
                new AgentAuthSupport(mock(AuthService.class)));

        assertEquals(0, controller.resolveResumeAfterSeq(null, "not-a-seq"));
        assertEquals(0, controller.resolveResumeAfterSeq(null, null));
        assertEquals(0, controller.resolveResumeAfterSeq(-1, "42"));
    }
}
