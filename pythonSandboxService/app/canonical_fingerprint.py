from __future__ import annotations

import hashlib
import hmac
import re
from dataclasses import dataclass

from .models import ExecuteRequest


CURRENT_SCHEMA_VERSION = "sandbox_create_v1"
SHA256_PATTERN = re.compile(r"^(?:sha256:)?([0-9a-fA-F]{64})$")


class CanonicalSpecError(ValueError):
    pass


class CanonicalFingerprintMismatch(RuntimeError):
    pass


def normalize_sha256(value: str | None, field: str) -> str:
    match = SHA256_PATTERN.fullmatch((value or "").strip())
    if match is None:
        raise CanonicalSpecError(f"{field} must be a SHA-256 hex digest")
    return "sha256:" + match.group(1).lower()


@dataclass(frozen=True)
class CanonicalSandboxCreateSpec:
    schema_version: str
    operation_id: str
    code_hash: str
    immutable_dataset_snapshot_digest: str
    resource_class: str
    memory_limit_bytes: int
    timeout_millis: int
    runtime_environment_version: str
    libraries_digest: str
    sandbox_options_digest: str

    def __post_init__(self) -> None:
        if self.schema_version != CURRENT_SCHEMA_VERSION:
            raise CanonicalSpecError(
                f"canonical_spec_schema_version must equal {CURRENT_SCHEMA_VERSION}"
            )
        if not self.operation_id.strip():
            raise CanonicalSpecError("operation_id must not be blank")
        if self.resource_class not in {"STANDARD", "HEAVY"}:
            raise CanonicalSpecError("resource_class must be STANDARD or HEAVY")
        if self.memory_limit_bytes <= 0:
            raise CanonicalSpecError("memory_limit_bytes must be positive")
        if self.timeout_millis <= 0:
            raise CanonicalSpecError("timeout_millis must be positive")
        if not self.runtime_environment_version.strip():
            raise CanonicalSpecError("runtime_environment_version must not be blank")
        object.__setattr__(self, "code_hash", normalize_sha256(self.code_hash, "code_hash"))
        object.__setattr__(
            self,
            "immutable_dataset_snapshot_digest",
            normalize_sha256(
                self.immutable_dataset_snapshot_digest,
                "immutable_dataset_snapshot_digest",
            ),
        )
        object.__setattr__(
            self,
            "libraries_digest",
            normalize_sha256(self.libraries_digest, "libraries_digest"),
        )
        object.__setattr__(
            self,
            "sandbox_options_digest",
            normalize_sha256(self.sandbox_options_digest, "sandbox_options_digest"),
        )

    def canonical_bytes(self) -> bytes:
        fields = (
            ("schemaVersion", self.schema_version),
            ("operationId", self.operation_id),
            ("codeHash", self.code_hash),
            ("immutableDatasetSnapshotDigest", self.immutable_dataset_snapshot_digest),
            ("resourceClass", self.resource_class),
            ("memoryLimitBytes", str(self.memory_limit_bytes)),
            ("timeoutMillis", str(self.timeout_millis)),
            ("runtimeEnvironmentVersion", self.runtime_environment_version),
            ("librariesDigest", self.libraries_digest),
            ("sandboxOptionsDigest", self.sandbox_options_digest),
        )
        output = bytearray()
        for field, value in fields:
            encoded = value.encode("utf-8")
            output.extend(f"{field}:{len(encoded)}:".encode("utf-8"))
            output.extend(encoded)
            output.extend(b"\n")
        return bytes(output)

    def request_fingerprint(self) -> str:
        return "sha256:" + hashlib.sha256(self.canonical_bytes()).hexdigest()


def spec_from_request(request: ExecuteRequest) -> CanonicalSandboxCreateSpec:
    if request.memory_limit_bytes is None or request.timeout_millis is None:
        raise CanonicalSpecError("memory_limit_bytes and timeout_millis are required for canonical verification")
    return CanonicalSandboxCreateSpec(
        schema_version=request.canonical_spec_schema_version or "",
        operation_id=request.operation_id or "",
        code_hash=request.code_hash or "",
        immutable_dataset_snapshot_digest=request.immutable_dataset_snapshot_digest or "",
        resource_class=request.resource_class,
        memory_limit_bytes=request.memory_limit_bytes,
        timeout_millis=request.timeout_millis,
        runtime_environment_version=request.runtime_environment_version or "",
        libraries_digest=request.libraries_digest or "",
        sandbox_options_digest=request.sandbox_options_digest or "",
    )


def verify_request_fingerprint(request: ExecuteRequest) -> str | None:
    if not request.operation_id:
        return None
    spec = spec_from_request(request)
    actual_code_hash = "sha256:" + hashlib.sha256(request.code.encode("utf-8")).hexdigest()
    if not hmac.compare_digest(spec.code_hash, actual_code_hash):
        raise CanonicalFingerprintMismatch("code_hash does not match the received code payload")
    expected = spec.request_fingerprint()
    supplied = normalize_sha256(request.request_fingerprint, "request_fingerprint")
    if not hmac.compare_digest(expected, supplied):
        raise CanonicalFingerprintMismatch(
            "request_fingerprint does not match the canonical sandbox create spec"
        )
    return expected
