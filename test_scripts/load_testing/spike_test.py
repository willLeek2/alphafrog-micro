"""
峰值流量测试（Spike）
目的：验证系统应对突发流量的能力

自定义负载形状：
  0-1min:  10用户（基线）
  1-2min:  快速升至200用户（突发）
  2-5min:  保持200用户（峰值保持）
  5-6min:  降回10用户（恢复）

运行命令：
  locust -f spike_test.py --host=http://localhost:8090
"""

import random

from locust import HttpUser, LoadTestShape, between, task


class SpikeTestUser(HttpUser):
    """峰值测试用户"""

    wait_time = between(0.5, 1.5)

    def on_start(self):
        """登录获取token"""
        self.token = None
        response = self.client.post(
            "/api/auth/login",
            json={
                "username": f"spike_user_{id(self)}",
                "password": "test_pass",
            },
        )
        if response.status_code == 200:
            self.token = response.json().get("token")

    @task(5)
    def query_hot_index(self):
        """查询热门指数 - 权重5（模拟热点）"""
        hot_indices = ["000300.SH", "000905.SH", "000016.SH"]
        ts_code = random.choice(hot_indices)
        headers = {}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        self.client.get(
            f"/api/market/index/quote?ts_code={ts_code}", headers=headers
        )

    @task(1)
    def query_cold_index(self):
        """查询冷门指数 - 权重1"""
        headers = {}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        self.client.get(
            "/api/market/index/quote?ts_code=399006.SZ", headers=headers
        )


class SpikeLoadShape(LoadTestShape):
    """
    自定义负载形状：模拟突发流量

    stages 中每个元素为 (持续时间秒, 目标用户数)
    """

    stages = [
        (60, 10),
        (60, 200),
        (180, 200),
        (60, 10),
    ]

    def tick(self):
        run_time = self.get_run_time()
        elapsed = 0
        for duration, user_count in self.stages:
            elapsed += duration
            if run_time < elapsed:
                spawn_rate = max(user_count // 10, 1)
                return (user_count, spawn_rate)
        return None
