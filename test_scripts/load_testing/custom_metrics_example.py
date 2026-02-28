"""
自定义Metrics收集示例
展示如何通过Locust事件钩子收集业务指标

运行命令：
  locust -f custom_metrics_example.py --host=http://localhost:8090
"""

import time

from locust import HttpUser, events, task
from locust.runners import MasterRunner

# 自定义业务指标
cache_hit_count = 0
cache_miss_count = 0


@events.request.add_listener
def on_request(request_type, name, response_time, response_length,
               response, context, exception, **kwargs):
    """每个请求结束时触发 - 可在此发送metrics到外部系统（如VictoriaMetrics）"""
    # 示例: 将数据推送到VictoriaMetrics
    # requests.post("http://localhost:8428/api/v1/import/prometheus",
    #     data=f'locust_response_time{{name="{name}"}} {response_time}')


@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    """测试开始时触发"""
    if isinstance(environment.runner, MasterRunner):
        print("测试开始，初始化监控...")


@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    """测试结束时触发"""
    if isinstance(environment.runner, MasterRunner):
        print(f"测试结束 | 缓存命中: {cache_hit_count} | 缓存未命中: {cache_miss_count}")


class MonitoredUser(HttpUser):
    """带自定义监控的用户"""

    @task
    def monitored_request(self):
        global cache_hit_count, cache_miss_count

        start = time.time()
        response = self.client.get("/api/market/quote")
        elapsed_ms = (time.time() - start) * 1000

        if response.status_code == 200:
            try:
                data = response.json()
                if data.get("from_cache"):
                    cache_hit_count += 1
                else:
                    cache_miss_count += 1
            except Exception:
                pass

        # 可选：将elapsed_ms发送到VictoriaMetrics
        # requests.post("http://localhost:8428/api/v1/import/prometheus",
        #     data=f'api_response_ms{{endpoint="market_quote"}} {elapsed_ms}')
