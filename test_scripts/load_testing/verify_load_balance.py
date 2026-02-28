"""
负载均衡验证测试
目的：验证多实例部署时Nginx能正确分发请求

运行命令：
  locust -f verify_load_balance.py --host=http://localhost:8080
"""

from collections import Counter

from locust import HttpUser, events, task

instance_counter = Counter()


@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    """测试结束时输出实例分布"""
    total = sum(instance_counter.values())
    if total > 0:
        print("\n========== 负载均衡分布 ==========")
        for instance_id, count in instance_counter.most_common():
            pct = count / total * 100
            print(f"  实例 {instance_id}: {count} 次 ({pct:.1f}%)")
        print(f"  总请求数: {total}")
        print("==================================\n")


class LoadBalanceVerifyUser(HttpUser):
    """验证负载均衡是否生效"""

    @task
    def check_instance_distribution(self):
        response = self.client.get("/api/market/index/health")
        if response.status_code == 200:
            try:
                instance_id = response.json().get("instance_id", "unknown")
                instance_counter[instance_id] += 1
            except Exception:
                pass
