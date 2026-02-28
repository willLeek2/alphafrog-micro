"""
Docker环境下的混沌测试辅助脚本
配合Locust压测使用

使用示例（在Locust测试期间运行）：
  python docker_chaos.py restart rabbitmq --delay 600
  python docker_chaos.py pause redis --duration 30
  python docker_chaos.py network-delay domestic-index-service --delay-ms 500 --duration 60
"""

import argparse
import subprocess
import time


class DockerChaos:
    """Docker混沌测试工具"""

    @staticmethod
    def restart_service(service_name, delay=0):
        """重启指定服务"""
        if delay > 0:
            print(f"[CHAOS] 等待 {delay}s 后重启 {service_name}...")
            time.sleep(delay)
        print(f"[CHAOS] 正在重启 {service_name}...")
        subprocess.run(["docker-compose", "restart", service_name], check=True)
        print(f"[CHAOS] {service_name} 已重启")

    @staticmethod
    def pause_service(service_name, duration, delay=0):
        """暂停服务指定时间"""
        if delay > 0:
            print(f"[CHAOS] 等待 {delay}s 后暂停 {service_name}...")
            time.sleep(delay)
        print(f"[CHAOS] 暂停 {service_name} {duration}s...")
        subprocess.run(["docker-compose", "pause", service_name], check=True)
        time.sleep(duration)
        subprocess.run(["docker-compose", "unpause", service_name], check=True)
        print(f"[CHAOS] {service_name} 已恢复")

    @staticmethod
    def network_delay(service_name, delay_ms, duration):
        """添加网络延迟"""
        container = f"alphafrog-{service_name.replace('_', '-')}"
        print(f"[CHAOS] 为 {container} 添加 {delay_ms}ms 网络延迟...")
        subprocess.run(
            [
                "docker", "exec", container,
                "tc", "qdisc", "add", "dev", "eth0", "root",
                "netem", "delay", f"{delay_ms}ms",
            ],
            check=True,
        )
        time.sleep(duration)
        subprocess.run(
            [
                "docker", "exec", container,
                "tc", "qdisc", "del", "dev", "eth0", "root",
            ],
            check=True,
        )
        print(f"[CHAOS] 已移除 {container} 的网络延迟")


def main():
    parser = argparse.ArgumentParser(description="Docker混沌测试工具")
    subparsers = parser.add_subparsers(dest="action", required=True)

    # restart
    restart_parser = subparsers.add_parser("restart", help="重启服务")
    restart_parser.add_argument("service", help="服务名称")
    restart_parser.add_argument("--delay", type=int, default=0, help="延迟秒数")

    # pause
    pause_parser = subparsers.add_parser("pause", help="暂停服务")
    pause_parser.add_argument("service", help="服务名称")
    pause_parser.add_argument("--duration", type=int, default=30, help="暂停持续秒数")
    pause_parser.add_argument("--delay", type=int, default=0, help="延迟秒数")

    # network-delay
    net_parser = subparsers.add_parser("network-delay", help="添加网络延迟")
    net_parser.add_argument("service", help="服务名称")
    net_parser.add_argument("--delay-ms", type=int, default=500, help="延迟毫秒数")
    net_parser.add_argument("--duration", type=int, default=60, help="持续秒数")

    args = parser.parse_args()
    chaos = DockerChaos()

    if args.action == "restart":
        chaos.restart_service(args.service, delay=args.delay)
    elif args.action == "pause":
        chaos.pause_service(args.service, args.duration, delay=args.delay)
    elif args.action == "network-delay":
        chaos.network_delay(args.service, args.delay_ms, args.duration)


if __name__ == "__main__":
    main()
