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
    def test_canonical_encoding_is_stable_across_key_order(self) -> None:
        from app.runtime_environment import _canonical_bytes, _sha256_hex_digest

        a = _canonical_bytes({"b": 1, "a": 2, "nested": {"y": 2, "x": 1}})
        b = _canonical_bytes({"a": 2, "nested": {"x": 1, "y": 2}, "b": 1})
        self.assertEqual(a, b)
        self.assertEqual(_sha256_hex_digest(a), _sha256_hex_digest(b))

    def test_inspect_container_image_digest_returns_sha256_form(self) -> None:
        from app.runtime_environment import _inspect_container_image_digest

        fake_client = _FakeDockerClient(
            _FakeContainer("sha256:abc123")
        )
        with patch.dict(
            "sys.modules",
            {"docker": types.SimpleNamespace(
                from_env=lambda: fake_client,
            )},
        ):
            result = _inspect_container_image_digest("container-xyz")

        self.assertEqual("sha256:abc123", result)

    def test_inspect_container_image_digest_returns_empty_on_unknown_container(self) -> None:
        from app.runtime_environment import _inspect_container_image_digest

        self.assertEqual("", _inspect_container_image_digest(""))
        self.assertEqual("", _inspect_container_image_digest("unknown"))

    def test_inspect_container_image_digest_returns_empty_on_sdk_failure(self) -> None:
        from app.runtime_environment import _inspect_container_image_digest

        class _BoomClient:
            @property
            def containers(self):
                boom_self = self

                class _BoomContainers:
                    def get(self, cid):
                        raise RuntimeError("docker daemon unreachable")

                return _BoomContainers()

            def close(self) -> None:
                return None

        boom = _BoomClient()
        with patch.dict(
            "sys.modules",
            {"docker": types.SimpleNamespace(from_env=lambda: boom)},
        ):
            result = _inspect_container_image_digest("container-xyz")

        self.assertEqual("", result)

    def test_inspect_container_image_digest_rejects_non_sha256_image(self) -> None:
        from app.runtime_environment import _inspect_container_image_digest

        fake_client = _FakeDockerClient(_FakeContainer("docker.io/library/python:3.12"))
        with patch.dict(
            "sys.modules",
            {"docker": types.SimpleNamespace(from_env=lambda: fake_client)},
        ):
            result = _inspect_container_image_digest("container-xyz")

        self.assertEqual("", result)

    def test_read_installed_packages_returns_inventory_complete_when_pip_succeeds(self) -> None:
        from app.runtime_environment import _read_installed_packages

        session = MagicMock()
        session.execute_command.return_value = _FakeCommandResult(
            stdout=json.dumps([
                {"name": "numpy", "version": "1.26.0"},
                {"name": "alphafrog_finance", "version": "1.0.3"},
                {"name": "Pandas", "version": "2.1.0"},
            ]),
            exit_code=0,
        )
        packages, complete = _read_installed_packages(session)

        self.assertTrue(complete)
        # _read_installed_packages preserves pip list insertion order; sorting
        # by name happens later in collect_runtime_environment for stable digest.
        self.assertEqual(
            ["numpy", "alphafrog_finance", "pandas"],
            [p["name"] for p in packages],
        )

    def test_read_installed_packages_returns_inventory_incomplete_on_failure(self) -> None:
        from app.runtime_environment import _read_installed_packages

        session = MagicMock()
        session.execute_command.return_value = _FakeCommandResult(
            stdout="", exit_code=1,
        )
        packages, complete = _read_installed_packages(session)

        self.assertFalse(complete)
        self.assertEqual([], packages)

    def test_read_installed_packages_returns_inventory_incomplete_on_no_session(self) -> None:
        from app.runtime_environment import _read_installed_packages

        packages, complete = _read_installed_packages(None)

        self.assertFalse(complete)
        self.assertEqual([], packages)

    def test_collect_runtime_environment_is_deterministic_for_same_inputs(self) -> None:
        from app.runtime_environment import collect_runtime_environment

        fake_client = _FakeDockerClient(_FakeContainer("sha256:image-digest-1"))

        session = _runtime_session(
            [
                {"name": "numpy", "version": "1.26.0"},
                {"name": "alphafrog_finance", "version": "1.0.3"},
            ],
            {"alphafrog_finance": "1.7"},
        )
        with patch.dict(
            "sys.modules",
            {"docker": types.SimpleNamespace(from_env=lambda: fake_client)},
        ):
            env_a = collect_runtime_environment(container_id="c-1", session=session)
            env_b = collect_runtime_environment(container_id="c-1", session=session)

        self.assertEqual(env_a.environment_id, env_b.environment_id)
        self.assertEqual(env_a.image_digest, "sha256:image-digest-1")
        self.assertEqual(env_a.image_digest, env_b.image_digest)
        self.assertEqual(env_a.library_set_digest, env_b.library_set_digest)
        self.assertEqual(env_a.package_apis, env_b.package_apis)
        self.assertTrue(env_a.inventory_complete)

    def test_collect_runtime_environment_changing_image_changes_environment_id(self) -> None:
        from app.runtime_environment import collect_runtime_environment

        fake_client_a = _FakeDockerClient(_FakeContainer("sha256:image-A"))
        fake_client_b = _FakeDockerClient(_FakeContainer("sha256:image-B"))
        session = _runtime_session(
            [{"name": "numpy", "version": "1.26.0"}], {}
        )
        with patch.dict(
            "sys.modules",
            {"docker": types.SimpleNamespace(from_env=lambda: fake_client_a)},
        ):
            env_a = collect_runtime_environment(container_id="c-A", session=session)
        with patch.dict(
            "sys.modules",
            {"docker": types.SimpleNamespace(from_env=lambda: fake_client_b)},
        ):
            env_b = collect_runtime_environment(container_id="c-B", session=session)

        self.assertNotEqual(env_a.environment_id, env_b.environment_id)
        self.assertEqual(env_a.library_set_digest, env_b.library_set_digest)

    def test_dynamic_install_changes_target_inventory_digest(self) -> None:
        from app.runtime_environment import collect_runtime_environment

        before = _runtime_session(
            [{"name": "numpy", "version": "1.26.0"}], {}
        )
        after = _runtime_session(
            [
                {"name": "numpy", "version": "1.26.0"},
                {"name": "alphafrog_finance", "version": "1.7.0"},
            ],
            {"alphafrog_finance": "1.7"},
        )
        env_before = collect_runtime_environment(
            container_id="same-container", session=before
        )
        env_after = collect_runtime_environment(
            container_id="same-container", session=after
        )

        self.assertNotEqual(env_before.library_set_digest, env_after.library_set_digest)
        self.assertNotEqual(env_before.environment_id, env_after.environment_id)
        self.assertIn(
            ".sandbox-venv/bin/python -m pip list",
            before.execute_command.call_args_list[0].args[0],
        )
        self.assertIn(
            ".sandbox-venv/bin/python -m pip list",
            after.execute_command.call_args_list[0].args[0],
        )

    def test_collect_runtime_environment_handles_full_failure_gracefully(self) -> None:
        from app.runtime_environment import collect_runtime_environment

        session = MagicMock()
        session.config.skip_environment_setup = False
        session.python_executable_path = "/sandbox/.sandbox-venv/bin/python"
        session.execute_command.return_value = _FakeCommandResult(
            stdout="", exit_code=1
        )
        env = collect_runtime_environment(container_id="c-broken", session=session)

        self.assertEqual("", env.image_digest)
        self.assertEqual([], env.package_apis)
        self.assertFalse(env.inventory_complete)
        self.assertTrue(env.environment_id.startswith("sha256:"))
        self.assertEqual(64, len(env.environment_id.split(":", 1)[1]))

    def test_collect_runtime_environment_single_source_consistency_with_file_write(self) -> None:
        from app.runtime_environment import (
            collect_runtime_environment,
            write_runtime_environment_json,
        )

        fake_client = _FakeDockerClient(_FakeContainer("sha256:image-X"))
        session = _runtime_session(
            [{"name": "alphafrog_finance", "version": "1.7.0"}],
            {"alphafrog_finance": "1.7"},
        )
        with patch.dict(
            "sys.modules",
            {"docker": types.SimpleNamespace(from_env=lambda: fake_client)},
        ):
            env = collect_runtime_environment(container_id="c-X", session=session)

        with tempfile.TemporaryDirectory() as tmp:
            workspace = Path(tmp)
            path = write_runtime_environment_json(str(workspace), env)
            self.assertEqual(str(workspace / "runtime-environment.json"), path)
            persisted = json.loads(Path(path).read_text(encoding="utf-8"))

        self.assertEqual(persisted["environment_id"], env.environment_id)
        self.assertEqual(persisted["image_digest"], env.image_digest)
        self.assertEqual(
            persisted["library_set_digest"], env.library_set_digest
        )
        self.assertEqual(
            [pkg.model_dump() for pkg in env.package_apis],
            persisted["package_apis"],
        )
        self.assertEqual(
            persisted["inventory_complete"], env.inventory_complete
        )

    def test_write_runtime_environment_json_creates_missing_workspace(self) -> None:
        from app.runtime_environment import (
            ExecutionEnvironment,
            SandboxPackageApi,
            write_runtime_environment_json,
        )

        env = ExecutionEnvironment(
            environment_id="sha256:abc",
            image_digest="sha256:img",
            library_set_digest="sha256:libs",
            package_apis=[
                SandboxPackageApi(name="numpy", version="1.0", api_version="1.0"),
            ],
            inventory_complete=True,
        )

        with tempfile.TemporaryDirectory() as tmp:
            nested = Path(tmp) / "deep" / "nested" / "workspace"
            path = write_runtime_environment_json(str(nested), env)
            self.assertTrue(os.path.exists(path))
            payload = json.loads(Path(path).read_text(encoding="utf-8"))
            self.assertEqual("sha256:abc", payload["environment_id"])
            self.assertEqual(1, len(payload["package_apis"]))

    def test_environment_id_matches_manual_canonical_sha256(self) -> None:
        """Pin the canonical encoding to a known SHA-256 to detect regressions
        in key ordering or whitespace handling that would silently change the
        environmentId emitted on the wire.
        """
        from app.runtime_environment import collect_runtime_environment

        fake_client = _FakeDockerClient(_FakeContainer("sha256:fixed-image"))
        session = _runtime_session(
            [
                {"name": "z-pkg", "version": "0.1"},
                {"name": "alphafrog_finance", "version": "1.0"},
            ],
            {"alphafrog_finance": "1.0"},
        )

        with patch.dict(
            "sys.modules",
            {"docker": types.SimpleNamespace(from_env=lambda: fake_client)},
        ):
            env = collect_runtime_environment(
                container_id="c-fixed", session=session,
            )

        # library_set_digest merges alphafrog_finance's API version into the
        # same canonical payload; z-pkg stays pure name/version.
        library_set_payload = [
            {"name": "alphafrog_finance", "version": "1.0", "apiVersion": "1.0"},
            {"name": "z-pkg", "version": "0.1"},
        ]
        library_set_bytes = json.dumps(
            library_set_payload, sort_keys=True,
            separators=(",", ":"), ensure_ascii=False,
        ).encode("utf-8")
        expected_library_digest = "sha256:" + hashlib.sha256(library_set_bytes).hexdigest()
        self.assertEqual(expected_library_digest, env.library_set_digest)

        package_apis_payload = [
            {
                "name": "alphafrog_finance",
                "version": "1.0",
                "api_version": "1.0",
            },
        ]
        snapshot_payload = {
            "image_digest": "sha256:fixed-image",
            "library_set_digest": expected_library_digest,
            "package_apis": package_apis_payload,
        }
        snapshot_bytes = json.dumps(
            snapshot_payload, sort_keys=True,
            separators=(",", ":"), ensure_ascii=False,
        ).encode("utf-8")
        expected_environment_id = "sha256:" + hashlib.sha256(snapshot_bytes).hexdigest()
        self.assertEqual(expected_environment_id, env.environment_id)
        snapshot_bytes = json.dumps(
            snapshot_payload, sort_keys=True,
            separators=(",", ":"), ensure_ascii=False,
        ).encode("utf-8")
        expected_environment_id = "sha256:" + hashlib.sha256(snapshot_bytes).hexdigest()
        self.assertEqual(expected_environment_id, env.environment_id)

    def test_no_fabricated_default_for_packages_without_api_version(self) -> None:
        """Spec §8: probe failures MUST NOT fabricate a default apiVersion.

        The wire default "1.0" used to be applied by an importlib fallback;
        that fallback was removed because it imported target packages in the
        service host and silently masked missing __api_version__ attributes.
        """
        from app import runtime_environment

        self.assertFalse(hasattr(runtime_environment, "DEFAULT_PACKAGE_API_VERSION"))

    def test_resolve_target_interpreter_uses_sandbox_venv_for_user_code(self) -> None:
        from app.runtime_environment import _resolve_target_interpreter

        session = MagicMock()
        session.config.skip_environment_setup = False
        session.using_existing_container = False
        session.python_executable_path = "/sandbox/.sandbox-venv/bin/python"
        self.assertEqual(
            "/sandbox/.sandbox-venv/bin/python",
            _resolve_target_interpreter(session),
        )

    def test_resolve_target_interpreter_uses_system_python_for_skipped_setup(self) -> None:
        from app.runtime_environment import _resolve_target_interpreter

        session = MagicMock()
        session.config.skip_environment_setup = True
        session.using_existing_container = False
        session.python_executable_path = "/sandbox/.sandbox-venv/bin/python"
        self.assertEqual("python", _resolve_target_interpreter(session))

    def test_probe_package_api_versions_reads_container_value(self) -> None:
        from app.runtime_environment import _probe_package_api_versions

        session = MagicMock()
        session.config = types.SimpleNamespace(skip_environment_setup=False)
        session.using_existing_container = False
        session.python_executable_path = "/sandbox/.sandbox-venv/bin/python"
        session.execute_command.return_value = _FakeCommandResult(
            stdout=json.dumps({"alphafrog_finance": "1.7"}), exit_code=0
        )
        versions, complete = _probe_package_api_versions(
            session, ["alphafrog_finance"]
        )
        self.assertTrue(complete)
        self.assertEqual({"alphafrog_finance": "1.7"}, versions)
        command = session.execute_command.call_args.args[0]
        self.assertIn(".sandbox-venv/bin/python", command)
        self.assertIn("__api_version__", command)

    def test_probe_package_api_versions_failure_is_incomplete(self) -> None:
        from app.runtime_environment import _probe_package_api_versions

        session = MagicMock()
        session.config = types.SimpleNamespace(skip_environment_setup=False)
        session.python_executable_path = "/sandbox/.sandbox-venv/bin/python"
        session.execute_command.return_value = _FakeCommandResult(
            stdout=json.dumps({"alphafrog_finance": "1.7"}), exit_code=0
        )
        _probe_package_api_versions(
            session, ["alphafrog_finance", "numpy", "beautifulsoup4"]
        )
        command = session.execute_command.call_args.args[0]
        self.assertIn("alphafrog_finance", command)
        self.assertNotIn("numpy", command)
        self.assertNotIn("beautifulsoup4", command)

    def test_library_set_digest_matches_golden_reference(self) -> None:
        """Pin the canonical librarySetDigest for a fixed inventory.

        This matches the H canonical JSON digest for the same packages and
        apiVersion; a divergence here signals a schema drift that would
        invalidate downstream compatibility checks.
        """
        from app.runtime_environment import collect_runtime_environment

        fake_client = _FakeDockerClient(_FakeContainer("sha256:golden-image"))
        session = _runtime_session(
            [
                {"name": "numpy", "version": "1.26.0"},
                {"name": "alphafrog_finance", "version": "1.0.3"},
            ],
            {"alphafrog_finance": "1.7"},
        )
        with patch.dict(
            "sys.modules",
            {"docker": types.SimpleNamespace(from_env=lambda: fake_client)},
        ):
            env = collect_runtime_environment(
                container_id="c-golden", session=session
            )
        self.assertEqual(
            "sha256:d36c2ee55acb03458cd694a28477f911e8c0ffa71e978bf280eabbea1ecb2ee1",
            env.library_set_digest,
        )

    def test_probe_package_api_versions_failure_is_incomplete(self) -> None:
        from app.runtime_environment import _probe_package_api_versions

        session = MagicMock()
        session.config.skip_environment_setup = False
        session.python_executable_path = "/sandbox/.sandbox-venv/bin/python"
        session.execute_command.return_value = _FakeCommandResult(
            stdout="", exit_code=1
        )
        versions, complete = _probe_package_api_versions(
            session, ["alphafrog_finance"]
        )
        self.assertFalse(complete)
        self.assertEqual({}, versions)

    def test_probe_package_api_versions_missing_attribute_is_incomplete(self) -> None:
        from app.runtime_environment import _probe_package_api_versions

        session = MagicMock()
        session.config = types.SimpleNamespace(skip_environment_setup=False)
        session.python_executable_path = "/sandbox/.sandbox-venv/bin/python"
        session.execute_command.return_value = _FakeCommandResult(
            stdout=json.dumps({"alphafrog_finance": None}), exit_code=0
        )
        versions, complete = _probe_package_api_versions(
            session, ["alphafrog_finance"]
        )
        self.assertFalse(complete)
        self.assertEqual({}, versions)

    def test_probe_does_not_import_unsupported_packages(self) -> None:
        from app.runtime_environment import (
            SUPPORTED_PACKAGE_API_NAMES,
            _probe_package_api_versions,
        )

        session = MagicMock()
        session.config = types.SimpleNamespace(skip_environment_setup=False)
        session.python_executable_path = "/sandbox/.sandbox-venv/bin/python"
        session.execute_command.return_value = _FakeCommandResult(
            stdout=json.dumps({"alphafrog_finance": "1.7"}), exit_code=0
        )
        _probe_package_api_versions(
            session, list(SUPPORTED_PACKAGE_API_NAMES)
        )
        command = session.execute_command.call_args.args[0]
        self.assertIn("alphafrog_finance", command)
        for unsupported in ("numpy", "beautifulsoup4", "scikit-learn"):
            self.assertNotIn(unsupported, command)

    def test_collect_runtime_environment_uses_container_api_version(self) -> None:
        from app import runtime_environment
        from app.runtime_environment import collect_runtime_environment

        fake_client = _FakeDockerClient(_FakeContainer("sha256:image-real-api"))
        session = _runtime_session(
            [
                {"name": "alphafrog_finance", "version": "1.0.0"},
                {"name": "numpy", "version": "1.26.0"},
            ],
            {"alphafrog_finance": "1.7"},
        )
        with patch.dict(
            "sys.modules",
            {"docker": types.SimpleNamespace(from_env=lambda: fake_client)},
        ):
            env = collect_runtime_environment(
                container_id="c-real-api", session=session,
            )
        by_name = {pkg.name: pkg for pkg in env.package_apis}
        self.assertEqual("1.7", by_name["alphafrog_finance"].api_version)
        self.assertNotIn("numpy", by_name)
        probe_command = session.execute_command.call_args_list[1].args[0]
        self.assertIn("alphafrog_finance", probe_command)
        self.assertNotIn("numpy", probe_command)
        self.assertTrue(env.inventory_complete)
        self.assertNotEqual(env.environment_id, env.library_set_digest)

    def test_collect_runtime_environment_marks_api_probe_failure_incomplete(self) -> None:
        from app.runtime_environment import collect_runtime_environment

        session = MagicMock()
        session.config.skip_environment_setup = False
        session.python_executable_path = "/sandbox/.sandbox-venv/bin/python"
        session.execute_command.side_effect = [
            _FakeCommandResult(
                stdout=json.dumps([{"name": "alphafrog_finance", "version": "1.0.0"}]),
                exit_code=0,
            ),
            _FakeCommandResult(stdout="not-json", exit_code=0),
        ]
        env = collect_runtime_environment(container_id="c-probe-fail", session=session)
        self.assertFalse(env.inventory_complete)
        self.assertEqual([], env.package_apis)
