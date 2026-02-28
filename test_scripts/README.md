# AlphaFrog-Micro 压测工具集

基于 [Locust](https://locust.io/) 的性能测试和混沌工程脚本集合。

## 环境准备

```bash
pip install -r test_scripts/requirements.txt
```

## 目录结构

```
test_scripts/
├── load_testing/                    # Locust压测脚本
│   ├── baseline_health_check.py     # 基准负载测试
│   ├── spike_test.py                # 峰值流量测试（含自定义LoadTestShape）
│   ├── soak_test.py                 # 长时间Soak测试
│   ├── agent_multi_turn_test.py     # Agent多轮对话测试
│   ├── custom_metrics_example.py    # 自定义Metrics收集示例
│   └── verify_load_balance.py       # 负载均衡验证
├── chaos/                           # 混沌测试工具
│   ├── docker_chaos.py              # Docker混沌测试工具（故障注入）
│   └── scale-service.sh             # 扩容脚本
├── docker-compose.scale.yml         # 多实例部署配置
├── nginx-lb.conf                    # Nginx负载均衡配置
├── requirements.txt                 # Python依赖
└── README.md                        # 本文件
```

## 常用命令

### 基准测试（Web UI）

```bash
locust -f test_scripts/load_testing/baseline_health_check.py \
  --host=http://localhost:8090
# 打开 http://localhost:8089 查看实时监控
```

### 峰值测试

```bash
locust -f test_scripts/load_testing/spike_test.py \
  --host=http://localhost:8090
```

### Soak测试（无头模式，4小时）

```bash
locust -f test_scripts/load_testing/soak_test.py --headless \
  --users 30 --spawn-rate 5 --run-time 4h \
  --host=http://localhost:8090 \
  --csv=reports/soak
```

### Agent多轮对话测试

```bash
locust -f test_scripts/load_testing/agent_multi_turn_test.py \
  --host=http://localhost:8090
```

### 生成CSV+HTML报告

```bash
locust -f test_scripts/load_testing/baseline_health_check.py --headless \
  --users 100 --spawn-rate 10 --run-time 5m \
  --host=http://localhost:8090 \
  --csv=reports/baseline --html=reports/baseline.html
```

## 混沌测试

在Locust压测期间执行以下命令模拟故障：

```bash
# 重启RabbitMQ（延迟10分钟后执行）
python test_scripts/chaos/docker_chaos.py restart rabbitmq --delay 600

# 暂停Redis 30秒
python test_scripts/chaos/docker_chaos.py pause redis --duration 30

# 为domestic-index-service添加500ms网络延迟，持续60秒
python test_scripts/chaos/docker_chaos.py network-delay domestic-index-service \
  --delay-ms 500 --duration 60
```

## 动态扩容

```bash
# 启动Nginx负载均衡 + 多实例
docker-compose -f docker-compose.yml \
  -f test_scripts/docker-compose.scale.yml up -d

# 扩展domestic-index-service到3个副本
./test_scripts/chaos/scale-service.sh domestic-index-service 3

# 验证负载均衡
locust -f test_scripts/load_testing/verify_load_balance.py \
  --host=http://localhost:8080
```

## 关键指标

| 指标 | 目标值 |
|------|--------|
| P99延迟 | < 2s |
| 错误率 | < 0.1% |
| 吞吐量 | > 100 RPS |
| CPU使用率 | < 70% |
| 内存使用率 | < 80% |
