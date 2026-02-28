"""
长时间Soak测试
目的：发现内存泄漏、连接池耗尽等长时间运行问题

运行命令（无头模式，运行4小时）：
  locust -f soak_test.py --headless \
    --users 30 --spawn-rate 5 --run-time 4h \
    --host=http://localhost:8090 \
    --csv=reports/soak
"""

import time

from locust import HttpUser, between, task


class SoakTestUser(HttpUser):
    """长时间浸泡测试用户"""

    wait_time = between(2, 5)

    def on_start(self):
        self.start_time = time.time()

    @task(1)
    def mixed_operations(self):
        """混合操作 - 模拟真实用户行为"""
        # 查询行情
        self.client.get("/api/market/index/quote?ts_code=000300.SH")

        # 5分钟后偶尔执行复杂查询
        if time.time() - self.start_time > 300:
            self.client.post(
                "/api/market/index/history",
                json={
                    "ts_code": "000300.SH",
                    "start_date": "20240101",
                    "end_date": "20241231",
                },
            )
