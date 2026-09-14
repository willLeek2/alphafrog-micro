package world.willfrog.alphafrogmicro.frontend.controller.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.ragfetchapi.RagFetchRequest;
import world.willfrog.ragfetchapi.RagFetchResponse;
import world.willfrog.ragfetchapi.RagFetchService;
import world.willfrog.alphafrogmicro.frontend.service.AdminUserAccessService;

import java.util.List;
import java.util.Map;

/**
 * RAG 元数据抓取触发入口（公网侧）。
 *
 * <p>校验当前 JWT 主体为启用状态的管理员用户，通过后通过 Dubbo 调用
 * domesticFetchService 的 RagFetchService 异步执行。
 *
 * @deprecated 请改用 {@code POST /tasks/create}（task_name=rag_ann_fetch 或 rag_report_fetch），
 *             此接口将在下一阶段删除。
 */
@Deprecated
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
@Slf4j
public class RagFetchTriggerController {

    private final AdminUserAccessService adminUserAccessService;

    @DubboReference
    private RagFetchService ragFetchService;

    @Deprecated
    @PostMapping("/fetch/trigger")
    public ResponseEntity<?> trigger(
            Authentication authentication,
            @RequestBody Map<String, Object> body) {

        if (!adminUserAccessService.isActiveAdmin(authentication)) {
            log.warn("[RagFetchTriggerController] 未授权的触发请求");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        String type = (String) body.get("type");
        String startDate = (String) body.get("startDate");
        String endDate = (String) body.get("endDate");

        if (type == null || type.isBlank() || startDate == null || endDate == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "type, startDate, endDate are required"));
        }
        if (!type.equals("announcement") && !type.equals("research_report") && !type.equals("both")) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "type must be announcement, research_report, or both"));
        }

        @SuppressWarnings("unchecked")
        List<String> industries = (List<String>) body.get("targetIndustries");

        RagFetchRequest req = RagFetchRequest.newBuilder()
                .setType(type)
                .setStartDate(startDate)
                .setEndDate(endDate)
                .addAllTargetIndustries(industries != null ? industries : List.of())
                .build();

        try {
            RagFetchResponse resp = ragFetchService.triggerRagFetch(req);
            log.info("[RagFetchTriggerController] 触发成功: status={}", resp.getStatus());
            return ResponseEntity.ok(Map.of("status", resp.getStatus(), "message", resp.getMessage()));
        } catch (Exception e) {
            log.error("[RagFetchTriggerController] 调用 RagFetchService 失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to trigger fetch"));
        }
    }

}
