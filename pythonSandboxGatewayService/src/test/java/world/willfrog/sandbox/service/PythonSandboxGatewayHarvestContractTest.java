package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 014-new-03 收割字段线上契约（泳道 b3 实测教训，CR 二轮要求补）：
 * HttpExecuteRequest 的 collect_artifacts / collect_artifact_max_bytes 在
 * null（开关关闭）时必须整个键不出现在序列化产物里——此前 mock 测试不经过
 * Jackson，null 被序列化成 "collect_artifacts": null 下发，Python 沙箱模型
 * 该字段是严格 bool，422 拒绝后 run 卡死。开关开启（true + 上限值）时两键
 * 必须在。用默认 ObjectMapper 复现 RestTemplate 消息转换器的缺省包含行为。
 */
class PythonSandboxGatewayHarvestContractTest {

    @Test
    void nullHarvestFieldsAreOmittedFromWireJson() throws Exception {
        String json = new ObjectMapper()
                .writeValueAsString(new PythonSandboxGatewayServiceImpl.HttpExecuteRequest());
        assertFalse(json.contains("collect_artifacts"), "null 时 collect_artifacts 键不得上线: " + json);
        assertFalse(json.contains("collect_artifact_max_bytes"),
                "null 时 collect_artifact_max_bytes 键不得上线: " + json);
    }

    @Test
    void harvestFieldsPresentOnWireJsonWhenSet() throws Exception {
        PythonSandboxGatewayServiceImpl.HttpExecuteRequest request =
                new PythonSandboxGatewayServiceImpl.HttpExecuteRequest();
        request.setCollect_artifacts(Boolean.TRUE);
        request.setCollect_artifact_max_bytes(10485760L);
        String json = new ObjectMapper().writeValueAsString(request);
        assertTrue(json.contains("\"collect_artifacts\":true"), "true 时键必须在: " + json);
        assertTrue(json.contains("\"collect_artifact_max_bytes\":10485760"), "上限值时键必须在: " + json);
    }
}
