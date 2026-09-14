package world.willfrog.alphafrogmicro.frontend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import world.willfrog.alphafrogmicro.frontend.config.TaskProducerRabbitConfig;
import world.willfrog.alphafrogmicro.frontend.service.AdminUserAccessService;
import world.willfrog.alphafrogmicro.frontend.service.FetchTaskStatusService;
import world.willfrog.alphafrogmicro.frontend.service.RateLimitingService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskControllerAdminAccessTest {

    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private RateLimitingService rateLimitingService;
    @Mock
    private FetchTaskStatusService fetchTaskStatusService;
    @Mock
    private AdminUserAccessService adminUserAccessService;
    @Mock
    private Authentication authentication;

    private TaskController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskController(
                rabbitTemplate,
                rateLimitingService,
                fetchTaskStatusService,
                adminUserAccessService);
        when(rateLimitingService.tryAcquire("task")).thenReturn(true);
    }

    @Test
    void normalUserCannotCreateRagFetchTask() {
        when(adminUserAccessService.isActiveAdmin(authentication)).thenReturn(false);

        ResponseEntity<String> response = controller.createTask(authentication, Map.of(
                "task_type", "fetch",
                "task_name", "rag_ann_fetch"));

        assertEquals(403, response.getStatusCode().value());
        verify(fetchTaskStatusService, never()).registerTask(anyString(), any(), any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString());
    }

    @Test
    void adminJwtCanCreateRagFetchTaskWithoutSecondarySecret() {
        when(adminUserAccessService.isActiveAdmin(authentication)).thenReturn(true);

        ResponseEntity<String> response = controller.createTask(authentication, Map.of(
                "task_type", "fetch",
                "task_name", "rag_report_fetch"));

        assertEquals(200, response.getStatusCode().value());
        verify(fetchTaskStatusService).registerTask(anyString(), eq("rag_report_fetch"), any());
        verify(rabbitTemplate).convertAndSend(
                eq(TaskProducerRabbitConfig.FETCH_EXCHANGE),
                eq(TaskProducerRabbitConfig.FETCH_TASK_ROUTING_KEY),
                anyString());
    }

    @Test
    void ordinaryFetchTaskKeepsExistingAuthenticatedUserBehavior() {
        ResponseEntity<String> response = controller.createTask(authentication, Map.of(
                "task_type", "fetch",
                "task_name", "domestic_stock_fetch"));

        assertEquals(200, response.getStatusCode().value());
        verify(adminUserAccessService, never()).isActiveAdmin(authentication);
        verify(rabbitTemplate).convertAndSend(
                eq(TaskProducerRabbitConfig.FETCH_EXCHANGE),
                eq(TaskProducerRabbitConfig.FETCH_TASK_ROUTING_KEY),
                anyString());
    }
}
