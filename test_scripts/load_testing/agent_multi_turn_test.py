"""
Agent多轮对话长时运行测试
目的：验证Agent Run在长时间运行下的稳定性

运行命令：
  locust -f agent_multi_turn_test.py --host=http://localhost:8090
"""

import random
import time

from locust import HttpUser, between, task


class AgentMultiTurnUser(HttpUser):
    """Agent多轮对话长时运行测试"""

    wait_time = between(5, 10)

    def on_start(self):
        """登录并初始化"""
        self.token = None
        response = self.client.post(
            "/api/auth/login",
            json={
                "username": f"agent_user_{id(self)}",
                "password": "test_pass",
            },
        )
        if response.status_code == 200:
            self.token = response.json().get("token")

    @task(1)
    def create_and_poll_run(self):
        """创建Run并轮询直到完成"""
        if not self.token:
            return

        queries = [
            "分析沪深300最近一年的走势",
            "对比中证500和沪深300的收益率",
            "查询贵州茅台的财务指标",
        ]

        with self.client.post(
            "/api/agent/runs",
            json={"query": random.choice(queries), "stream": False},
            headers={"Authorization": f"Bearer {self.token}"},
            catch_response=True,
        ) as create_res:
            if create_res.status_code != 200:
                create_res.failure(
                    f"Failed to create run: {create_res.status_code}"
                )
                return

            run_id = create_res.json().get("run_id")
            if not run_id:
                create_res.failure("No run_id in response")
                return

        # 轮询检查状态（最多等待5分钟）
        max_attempts = 60
        for _attempt in range(max_attempts):
            time.sleep(5)

            with self.client.get(
                f"/api/agent/runs/{run_id}",
                headers={"Authorization": f"Bearer {self.token}"},
                catch_response=True,
            ) as status_res:
                if status_res.status_code != 200:
                    status_res.failure(
                        f"Failed to get run status: {status_res.status_code}"
                    )
                    return

                status = status_res.json().get("status")

                if status == "completed":
                    status_res.success()
                    return
                elif status == "failed":
                    status_res.failure("Run failed")
                    return

        # 超时
        self.environment.events.request.fire(
            request_type="POLL",
            name="/api/agent/runs/[run_id] (timeout)",
            response_time=max_attempts * 5000,
            response_length=0,
            response=None,
            context={},
            exception=TimeoutError("Run polling timeout"),
        )
