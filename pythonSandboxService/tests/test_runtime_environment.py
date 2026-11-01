from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch


# 260808-finance-methodspec-v5 work package D: tests for the single-source
# runtime environment generator. The contract requires the same
# ExecutionEnvironment instance to drive both the workdir file and the HTTP
# execution_environment field; these tests pin the canonical encoding rules
# and the inventory_complete semantics.


class _FakeCommandResult:
    def __init__(self, stdout: str = "", exit_code: int = 0) -> None:
        self.stdout = stdout
        self.exit_code = exit_code


def _runtime_session(
    packages: list[dict], api_versions: dict[str, str | None] | None = None
) -> MagicMock:
    session = MagicMock()
    session.config = types.SimpleNamespace(skip_environment_setup=False)
    session.using_existing_container = False
    session.python_executable_path = "/sandbox/.sandbox-venv/bin/python"
    def execute(command: str) -> _FakeCommandResult:
        if "-m pip list" in command:
            return _FakeCommandResult(stdout=json.dumps(packages), exit_code=0)
        return _FakeCommandResult(
            stdout=json.dumps(api_versions or {}), exit_code=0
        )

    session.execute_command.side_effect = execute
    return session


class _FakeContainer:
    def __init__(self, image: str) -> None:
        self._attrs = {"Image": image}

    def reload(self) -> None:
        return None

    @property
    def attrs(self) -> dict:
        return self._attrs


class _FakeDockerClient:
    def __init__(self, container: _FakeContainer | None) -> None:
        # Docker SDK exposes client.containers.get(container_id); mirror the
        # two-step attribute chain with SimpleNamespace so the mock has the
        # same surface as the real client.
        self.containers = types.SimpleNamespace(get=lambda cid: container)
        self._container = container

    def close(self) -> None:
        return None


class RuntimeEnvironmentTest(unittest.TestCase):
    pass
