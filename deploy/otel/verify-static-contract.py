#!/usr/bin/env python3
"""Verify the repository-side observability contract without Docker or services."""

from __future__ import annotations

import hashlib
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[2]
COMPOSE = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")

SERVICES = {
    "domestic-stock-service": ("domesticStockService", "domestic-stock-service", "AF_BUILD_IMAGE_ID_DOMESTIC_STOCK_SERVICE"),
    "domestic-index-service": ("domesticIndexService", "domestic-index-service", "AF_BUILD_IMAGE_ID_DOMESTIC_INDEX_SERVICE"),
    "domestic-fund-service": ("domesticFundService", "domestic-fund-service", "AF_BUILD_IMAGE_ID_DOMESTIC_FUND_SERVICE"),
    "domestic-listed-asset-service": ("domesticListedAssetService", "domestic-listed-asset-service", "AF_BUILD_IMAGE_ID_DOMESTIC_LISTED_ASSET_SERVICE"),
    "domestic-fetch-service": ("domesticFetchService", "domestic-fetch-service", "AF_BUILD_IMAGE_ID_DOMESTIC_FETCH_SERVICE"),
    "admin-service": ("adminService", "admin-service", "AF_BUILD_IMAGE_ID_ADMIN_SERVICE"),
    "portfolio-service": ("portfolioService", "portfolio-service", "AF_BUILD_IMAGE_ID_PORTFOLIO_SERVICE"),
    "agent-langchain-service": ("agentLangchainService", "agentLangchainService", "AF_BUILD_IMAGE_ID_AGENT_LANGCHAIN_SERVICE"),
    "external-info-service": ("externalInfoService", "external-info-service", "AF_BUILD_IMAGE_ID_EXTERNAL_INFO_SERVICE"),
    "python-sandbox-gateway-service": ("pythonSandboxGatewayService", "python-sandbox-gateway-service", "AF_BUILD_IMAGE_ID_PYTHON_SANDBOX_GATEWAY_SERVICE"),
    "frontend": ("frontend", "alphafrog-frontend", "AF_BUILD_IMAGE_ID_FRONTEND"),
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def service_block(service_name: str) -> str:
    lines = COMPOSE.splitlines()
    start = next(i for i, line in enumerate(lines) if line == f"  {service_name}:")
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if re.fullmatch(r"  [a-z0-9][a-z0-9-]*:", lines[i]):
            end = i
            break
    return "\n".join(lines[start:end])


def verify_compose() -> int:
    checks = 0
    require("x-otel-env: &otel-env" in COMPOSE, "compose 缺少共用 OpenTelemetry 环境锚点")
    require("AF_BUILD_IMAGE_DIGEST" not in COMPOSE, "compose 仍使用错误的共用镜像身份变量")
    checks += 2

    image_variables: list[str] = []
    for service_name, (module, otel_name, image_variable) in SERVICES.items():
        block = service_block(service_name)
        require("*otel-env" in block, f"{service_name} 未合并共用 OpenTelemetry 环境")
        require(f"OTEL_SERVICE_NAME: {otel_name}" in block, f"{service_name} 的 OTEL_SERVICE_NAME 不一致")
        require(f"image.digest=${{{image_variable}:-unknown}}" in block, f"{service_name} 未使用自己的本地 Image ID 变量")
        require("deployment.id=${AF_DEPLOYMENT_ID:-stable}" in block, f"{service_name} 缺 deployment.id")
        require("lane.tag=${AF_LANE_TAG:-stable}" in block, f"{service_name} 缺 lane.tag")
        require("service.version=${AF_BUILD_VERSION:-local}" in block, f"{service_name} 缺 service.version")
        require("git.commit=${AF_BUILD_COMMIT:-unknown}" in block, f"{service_name} 缺 git.commit")
        require("-javaagent:/otel/javaagent.jar" in block, f"{service_name} 未加载 Java Agent")
        require("./deploy/otel/opentelemetry-javaagent.jar:/otel/javaagent.jar:ro" in block, f"{service_name} 未只读挂载 Java Agent")
        require(f"./data/logs/{service_name}:/app/logs" in block, f"{service_name} 未挂载独立日志目录")
        dockerfile = (ROOT / module / "Dockerfile").read_text(encoding="utf-8")
        require(not re.search(r"(?m)^\s*USER\b", dockerfile), f"{service_name} 已改成非 root 镜像，需要先设计专用日志用户组")
        require(not re.search(r"(?m)^    user:\s*", block), f"{service_name} 已在 Compose 改成非 root，需要先设计专用日志用户组")
        image_variables.append(image_variable)
        checks += 12
    require(len(set(image_variables)) == len(SERVICES), "多个服务复用了同一个镜像身份变量")
    checks += 1

    collector = service_block("otel-collector")
    require("image: otel/opentelemetry-collector-contrib:0.159.0" in collector, "采集器镜像未固定版本")
    require("${AF_OTEL_VICTORIALOGS_ENDPOINT:-http://victorialogs:9428/insert/opentelemetry}" in collector, "VictoriaLogs 地址不可移植")
    require("AF_OTEL_FILE_STORAGE_DIRECTORY: /var/lib/otelcol" in collector, "采集器持久存储目录没有固定到挂载点")
    require("./data/logs:/var/log/apps:ro" in collector, "采集器未只读挂载所有应用日志")
    require("alphafrog_otelcoldata:/var/lib/otelcol" in collector, "采集器读取位置和发送队列没有持久卷")
    require('user: "0:0"' in collector, "采集器没有以容器 root 读取 0700 日志目录")
    checks += 6

    agent = service_block("agent-langchain-service")
    gateway = "https://llm.frogwch.com/openrouter/api/v1"
    require(agent.count(gateway) == 2, "OpenRouter 新加坡网关没有同时覆盖两个兼容配置入口")
    require(agent.count("llm.frogwch.com") >= 5, "新加坡网关没有同时进入 Java 与大小写代理排除配置")
    checks += 2
    return checks


def verify_logging() -> int:
    checks = 0
    expected_logback: bytes | None = None
    for service_name, (module, _, _) in SERVICES.items():
        pom = ROOT / module / "pom.xml"
        tree = ET.parse(pom)
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        dependencies = {
            (dep.findtext("m:groupId", namespaces=ns), dep.findtext("m:artifactId", namespaces=ns), dep.findtext("m:version", namespaces=ns))
            for dep in tree.findall("m:dependencies/m:dependency", ns)
        }
        require(("net.logstash.logback", "logstash-logback-encoder", "8.0") in dependencies, f"{service_name} 缺 LogstashEncoder 8.0")
        require(not any(artifact == "spring-boot-starter-log4j2" for _, artifact, _ in dependencies), f"{service_name} 同时保留 Log4j2，日志后端冲突")

        logback = ROOT / module / "src/main/resources/logback-spring.xml"
        data = logback.read_bytes()
        ET.parse(logback)
        require(b'/app/logs/app.log' in data, f"{service_name} 日志文件路径错误")
        require(b'<maxFileSize>50MB</maxFileSize>' in data, f"{service_name} 单文件上限错误")
        require(b'<maxHistory>3</maxHistory>' in data, f"{service_name} 日志保留天数错误")
        require(b'<totalSizeCap>500MB</totalSizeCap>' in data, f"{service_name} 日志总量上限错误")
        require(b'{"service":"${appName}","deployment":"${AF_DEPLOYMENT_ID:-stable}"}' in data, f"{service_name} 日志身份字段错误")
        if expected_logback is None:
            expected_logback = data
        require(data == expected_logback, f"{service_name} 的 logback 配置与其余服务不一致")
        checks += 8
    return checks


def parse_version_file(path: pathlib.Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            result[key] = value
    return result


def verify_collector() -> int:
    config_path = ROOT / "deploy/otel/otel-collector-config.yaml"
    config = config_path.read_text(encoding="utf-8")
    version = parse_version_file(ROOT / "deploy/otel/collector.version")
    actual_hash = hashlib.sha256(config_path.read_bytes()).hexdigest()
    require(version["OTEL_COLLECTOR_CONFIG_SHA256"] == actual_hash, "采集器配置摘要不一致")
    require("endpoint: ${env:AF_OTEL_VICTORIALOGS_ENDPOINT}" in config, "采集器导出地址被写死")
    require("directory: ${env:AF_OTEL_FILE_STORAGE_DIRECTORY}" in config, "采集器持久目录不可移植")
    require("create_directory: true" in config, "采集器不能自行创建持久目录")
    require(config.count("storage: file_storage") == 2, "读取位置与发送队列没有同时使用持久存储")
    require("/var/log/apps/*/*.log" in config, "采集器日志文件匹配规则错误")
    require('field: attributes["parse.error"]' in config, "坏 JSON 没有 parse.error 标记")
    parse_error_index = config.index('field: attributes["parse.error"]')
    message_move_index = config.index("from: attributes.message")
    require(parse_error_index < message_move_index, "parse.error 必须在 message 被移走以前判断")
    require("deployment" not in re.sub(r"#.*", "", config), "采集器不应覆盖日志 deployment 字段")
    require("headers" not in config and "body_capture" not in config, "采集器配置开启了秘密或正文采集")
    subprocess.run(["bash", str(ROOT / "deploy/otel/verify-collector-config.sh")], check=True, cwd=ROOT)
    checks = 11

    try:
        import yaml  # type: ignore
    except ImportError:
        pass
    else:
        yaml.safe_load(config)
        yaml.safe_load(COMPOSE)
        checks += 2
    return checks


def verify_preflight_without_docker() -> int:
    agent_image_id = "sha256:" + "a" * 64
    frontend_image_id = "sha256:" + "b" * 64
    with tempfile.TemporaryDirectory(prefix="otel-preflight-") as temp_dir:
        temp = pathlib.Path(temp_dir)
        fake_docker = temp / "docker"
        fake_docker.write_text(
            "#!/bin/sh\n"
            "if [ \"${AF_TEST_DUPLICATE_IMAGE_IDS:-0}\" = 1 ]; then\n"
            f"  printf '%s\\n' '{agent_image_id}'\n"
            "  exit 0\n"
            "fi\n"
            "for argument in \"$@\"; do image_ref=\"$argument\"; done\n"
            "case \"$image_ref\" in\n"
            f"  *agent-langchain-service*) printf '%s\\n' '{agent_image_id}' ;;\n"
            f"  *frontend*) printf '%s\\n' '{frontend_image_id}' ;;\n"
            "  *) exit 1 ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        fake_docker.chmod(0o755)
        output = temp / "runtime.env"
        env = {
            "PATH": f"{temp}:{os.environ['PATH']}",
            "AF_OTEL_RUNTIME_ENV_FILE": str(output),
            "AF_BUILD_VERSION": "test-version",
            "AF_BUILD_COMMIT": "b" * 40,
            "AF_DEPLOYMENT_ID": "stable",
            "AF_LANE_TAG": "stable",
        }
        subprocess.run(
            ["bash", str(ROOT / "deploy/otel/prepare-runtime-env.sh"), "agent-langchain-service", "frontend"],
            check=True,
            cwd=ROOT,
            env=env,
        )
        result = output.read_text(encoding="utf-8")
        require("AF_BUILD_IMAGE_ID_AGENT_LANGCHAIN_SERVICE=" + agent_image_id in result, "预检没有写 agent 服务的 Image ID")
        require("AF_BUILD_IMAGE_ID_FRONTEND=" + frontend_image_id in result, "预检没有写 frontend 的 Image ID")
        require((output.stat().st_mode & 0o777) == 0o600, "运行时身份文件权限不是 0600")

        duplicate_output = temp / "duplicate.env"
        duplicate_output.write_text("existing-valid-file\n", encoding="utf-8")
        duplicate_env = dict(env)
        duplicate_env["AF_OTEL_RUNTIME_ENV_FILE"] = str(duplicate_output)
        duplicate_env["AF_TEST_DUPLICATE_IMAGE_IDS"] = "1"
        duplicate = subprocess.run(
            ["bash", str(ROOT / "deploy/otel/prepare-runtime-env.sh"), "agent-langchain-service", "frontend"],
            cwd=ROOT,
            env=duplicate_env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        require(duplicate.returncode != 0, "预检错误接受了两个服务使用同一个 Image ID")
        require("指向同一个本地 Image ID" in duplicate.stderr, "重复 Image ID 的拒绝原因不明确")
        require(duplicate_output.read_text(encoding="utf-8") == "existing-valid-file\n", "重复 Image ID 被拒绝后仍覆盖了运行时身份文件")

        invalid_cases = (
            ("AF_DEPLOYMENT_ID", "stable-also-invalid-"),
            ("AF_LANE_TAG", "other-lane"),
        )
        for variable, value in invalid_cases:
            invalid_env = dict(env)
            invalid_env[variable] = value
            completed = subprocess.run(
                ["bash", str(ROOT / "deploy/otel/prepare-runtime-env.sh"), "agent-langchain-service"],
                cwd=ROOT,
                env=invalid_env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            require(completed.returncode != 0, f"预检错误接受了 {variable}={value}")
    return 8


def verify_log_directory_permissions() -> int:
    script = ROOT / "deploy/otel/prepare-log-directories.sh"
    deploy_script = (ROOT / "deploy_latest.sh").read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory(prefix="otel-log-permissions-") as temp_dir:
        log_root = pathlib.Path(temp_dir) / "logs"
        env = dict(os.environ)
        env["AF_OTEL_LOG_ROOT_DIR"] = str(log_root)

        unknown_root = pathlib.Path(temp_dir) / "unknown-logs"
        unknown_env = dict(env)
        unknown_env["AF_OTEL_LOG_ROOT_DIR"] = str(unknown_root)
        unknown = subprocess.run(
            ["bash", str(script), "unknown-service"],
            cwd=ROOT,
            env=unknown_env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        require(unknown.returncode != 0, "日志目录准备错误接受了未知服务")
        require(not unknown_root.exists(), "未知服务被拒绝前已经创建了日志目录")

        subprocess.run(
            ["bash", str(script), "agent-langchain-service", "frontend"],
            check=True,
            cwd=ROOT,
            env=env,
        )
        directories = (log_root, log_root / "agent-langchain-service", log_root / "frontend")
        for directory in directories:
            require((directory.stat().st_mode & 0o777) == 0o700, f"新建日志目录权限不是 0700：{directory}")
            require(directory.stat().st_uid == os.getuid(), f"新建日志目录不属于当前部署账号：{directory}")

        unsafe_directory = log_root / "frontend"
        unsafe_directory.chmod(0o755)
        unsafe = subprocess.run(
            ["bash", str(script), "frontend"],
            cwd=ROOT,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        require(unsafe.returncode != 0, "日志目录准备错误接受了已有 0755 目录")
        require("权限必须是 0700" in unsafe.stderr, "日志目录权限拒绝原因不明确")
        require((unsafe_directory.stat().st_mode & 0o777) == 0o755, "部署脚本静默修改了已有日志目录权限")

    require("prepare-log-directories.sh" in deploy_script, "部署脚本没有调用日志目录权限预检")
    require("chmod 0755" not in deploy_script, "部署脚本仍会把日志目录放宽到 0755")
    return 13


def main() -> int:
    total = (
        verify_compose()
        + verify_logging()
        + verify_collector()
        + verify_preflight_without_docker()
        + verify_log_directory_permissions()
    )
    print(f"observability static contract: {total} checks passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, ValueError, ET.ParseError) as exc:
        print(f"observability static contract: FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
