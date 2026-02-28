"""
基准负载测试（Baseline）
目的：确定系统在正常负载下的性能基线

运行命令：
  Web UI模式:
    locust -f baseline_health_check.py --host=http://localhost:8090
  无头模式:
    locust -f baseline_health_check.py --headless \
      --users 50 --spawn-rate 5 --run-time 5m \
      --host=http://localhost:8090 \
      --csv=reports/baseline
"""

from locust import HttpUser, task, between


class BaselineUser(HttpUser):
    """基准测试用户 - 模拟正常访问模式"""

    wait_time = between(1, 3)

    def on_start(self):
        """每个用户启动时执行（模拟登录）"""
        self.client.post(
            "/api/auth/login",
            json={
                "username": f"test_user_{id(self)}",
                "password": "test_pass",
            },
        )

    @task(3)
    def health_check(self):
        """健康检查 - 权重3"""
        with self.client.get("/api/health", catch_response=True) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"Health check failed: {response.status_code}")

    @task(1)
    def get_market_overview(self):
        """获取市场概览 - 权重1"""
        self.client.get("/api/market/overview")
