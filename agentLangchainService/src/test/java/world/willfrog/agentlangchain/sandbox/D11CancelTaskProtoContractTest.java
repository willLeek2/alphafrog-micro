package world.willfrog.agentlangchain.sandbox;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.OperationCancelTarget;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxErrorDetail;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D11 cancelTask proto 合同的极窄可回归断言（codex `f25b394a` 第 七 节推荐）。
 *
 * 目的：把「proto 形状正确」从聊天记录里的手工 grep 升级为仓库内 mvn test 可执行
 * 的断言。后续 proto 变更（如 v5 enum 扩展、字段复用）若打破这些断言，会在
 * agentLangchainService test 阶段立刻失败，而不是等 codex 复核时才发现。
 *
 * 不覆盖业务语义（CANCEL_INTENT_RECORDED 何时返回、fingerprint 不匹配走哪个
 * errorDetail.category 等）—— 这些是合同文档层约束，由实现 reviewer 把关。
 * 本测试只锁住生成 API 的形状属性。
 */
class D11CancelTaskProtoContractTest {

    @Test
    void settingSecondOneofTargetOverridesFirst() {
        // oneof 内只能设一个 target；set 第二个会让 getTargetCase 切换。
        // 这是 proto3 oneof 语义，但本断言锁住「生成代码实际表现为切换而非累加」，
        // 防止未来 proto 重构（例如改成两个 optional 字段）悄悄打破互斥性。
        CancelTaskRequest req = CancelTaskRequest.newBuilder()
                .setByTaskId(world.willfrog.alphafrogmicro.sandbox.idl.TaskIdCancelTarget.newBuilder()
                        .setTaskId("task-001")
                        .build())
                .build();
        assertEquals(CancelTaskRequest.TargetCase.BY_TASK_ID, req.getTargetCase());
        assertTrue(req.hasByTaskId());
        assertFalse(req.hasByOperation());

        // 再 set by_operation，by_task_id 应被清掉。
        CancelTaskRequest req2 = req.toBuilder()
                .setByOperation(OperationCancelTarget.newBuilder()
                        .setOperationId("op-002")
                        .setRequestFingerprint("fp-002")
                        .build())
                .build();
        assertEquals(CancelTaskRequest.TargetCase.BY_OPERATION, req2.getTargetCase());
        assertTrue(req2.hasByOperation());
        assertFalse(req2.hasByTaskId());
        assertNotEquals("task-001", req2.getByTaskId().getTaskId(),
                "by_task_id 应被 oneof 切换清空，原值不应残留");
    }

    @Test
    void emptyOperationCancelTargetStillReportsPresence() {
        // 空 sub-message（不设任何字段）仍会让 hasByOperation=true，证明运行时校验
        // 不可省：Gateway/Python 必须主动校验 operationId 与 requestFingerprint 非空，
        // 不能靠 hasByOperation() false 来拒绝半个请求（codex a3aee2ad 第 二 节）。
        CancelTaskRequest req = CancelTaskRequest.newBuilder()
                .setByOperation(OperationCancelTarget.newBuilder().build()) // 空 sub-message
                .build();
        assertTrue(req.hasByOperation(),
                "空 OperationCancelTarget 仍 hasByOperation=true，运行时必须校验内部字段非空");
        assertTrue(req.getTargetCase().equals(CancelTaskRequest.TargetCase.BY_OPERATION));
        assertEquals("", req.getByOperation().getOperationId(),
                "空 sub-message 内 operationId 默认空字符串，证明编译期无法保证非空");
        assertEquals("", req.getByOperation().getRequestFingerprint(),
                "空 sub-message 内 requestFingerprint 默认空字符串，证明编译期无法保证非空");
    }

    @Test
    void errorDetailPresenceFollowsParentMessageSemantics() {
        // D13 typed error 复用：父消息 presence（hasErrorDetail()）是新生产方信号。
        // absent=未分类（调用方按 D13 legacy 行为保守处理）；present=D13 mapping applied。
        // 本断言锁住「presence 由父消息控制，不是某个 category 值」。
        CancelTaskResponse withoutDetail = CancelTaskResponse.newBuilder()
                .setOutcome(world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome.CANCELED)
                .build();
        assertFalse(withoutDetail.hasErrorDetail(),
                "未 set errorDetail 时 hasErrorDetail()=false（D13 legacy/未分类语义）");

        CancelTaskResponse withDetail = CancelTaskResponse.newBuilder()
                .setOutcome(world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED)
                .setErrorDetail(SandboxErrorDetail.newBuilder()
                        .setCategory(world.willfrog.alphafrogmicro.sandbox.idl.SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT)
                        .build())
                .build();
        assertTrue(withDetail.hasErrorDetail(),
                "set errorDetail 后 hasErrorDetail()=true（D13 mapping applied）");
        assertNotNull(withDetail.getErrorDetail());
        assertEquals(
                world.willfrog.alphafrogmicro.sandbox.idl.SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT,
                withDetail.getErrorDetail().getCategory(),
                "category 是 D13 enum 引用，不是 string 复制（codex f25b394a 第 一 节 + a3aee2ad 第 六 节 5）");
    }

    @Test
    void serviceInterfaceDeclaresCancelTaskRpc() throws NoSuchMethodException {
        // PythonSandboxService 接口必须声明 cancelTask 方法 —— 防止未来 proto 重构
        // 误删 RPC。用 Java 反射查接口声明的方法，不依赖 Dubbo 内部 ServiceDescriptor
        // API（那套 API 在不同 Dubbo 版本里签名不稳定，本测试用最稳的 java.lang.reflect）。
        // 同步 + Async 都断言：proto3 RPC 生成的 Dubbo 接口对一定成对出现，少一个会
        // 立即触发下游 source-compatibility 编译错误（codex f25b394a 第 一 节教训）。
        java.lang.reflect.Method sync = PythonSandboxService.class.getMethod(
                "cancelTask", world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskRequest.class);
        assertNotNull(sync, "PythonSandboxService 必须声明 cancelTask(CancelTaskRequest) 同步方法");
        assertEquals(world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse.class,
                sync.getReturnType(),
                "cancelTask 同步方法必须返回 CancelTaskResponse");

        java.lang.reflect.Method async = PythonSandboxService.class.getMethod(
                "cancelTaskAsync", world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskRequest.class);
        assertNotNull(async, "PythonSandboxService 必须声明 cancelTaskAsync(CancelTaskRequest) 异步方法");
        assertEquals(java.util.concurrent.CompletableFuture.class, async.getReturnType(),
                "cancelTaskAsync 异步方法必须返回 CompletableFuture<CancelTaskResponse>");
    }
}
