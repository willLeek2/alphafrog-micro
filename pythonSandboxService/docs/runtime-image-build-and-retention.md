# Runtime Image Build and Retention (MethodSpec V5, Spec §12)

NEW FILE — work package H of the finance MethodSpec V5 plan.

Authoritative spec: 《金融MethodSpec-V5-源码实施与Agent分工计划.md》 §12
(work package H: 镜像、软件清单、摘要固定和保留, lines 1277–1361). Companion
contract: `agentLangchainService/docs/finance-methodspec-v5-contract.md`
(§4.2 digest rules; §5.1 `execution_environment`). This document describes
what the committed scripts actually do; read them together with this doc:

- `scripts/build_runtime_manifest.py` — manifest builder (library-set.json +
  external digest mapping + `AF_SANDBOX_IMAGE` validation helpers).
- `scripts/prune_runtime_images.sh` — plan-by-default image retention.
- `Dockerfile.runtime`, `docker_build.sh`, `requirements-image.lock` — build
  wiring described below.

Golden rule (Spec §12 实施要求, line 1311): **agents never push to origin and
never invent production values**. Base-image digests, SBOM digests and
registry references are pinned by frog (the human release owner) at release
time. Until a verified value exists, the code carries explicit
`REPLACE_WITH_...` placeholder tokens that are impossible to mistake for real
values and fail loudly.

Hardening round: the release gates are **fail-closed**. A runtime build with
missing or placeholder release inputs fails by default; the only escape hatch
is the explicit, independent dev switch
`AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD=true/1` (never implicit), and even
then the build is marked `releasable=false` and `deploy_latest.sh` refuses to
deploy it unless the same explicit switch is set. Digest-reference validation
is unified across all entry points (`app/config.py`,
`scripts/build_runtime_manifest.py`, `deploy_latest.sh`,
`docker_build.sh`): a pinned runtime image ref must be EXACTLY
`<repo>@sha256:<64 lowercase hex>` — anchored full match, lowercase only —
pinned by the shared vectors in `tests/digest_reference_vectors.py`.

## 1. Base image pinned by verified digest

Spec §12 line 1304: 基础镜像固定到已验证摘要，不使用裸 `python:3.13-slim` 标签。

`Dockerfile.runtime`:

```dockerfile
ARG RUNTIME_BASE_IMAGE_REF=REPLACE_WITH_VERIFIED_BASE_IMAGE_REF
FROM ${RUNTIME_BASE_IMAGE_REF}
```

`REPLACE_WITH_VERIFIED_BASE_IMAGE_REF` is a placeholder, not an image
reference: `docker build` fails at `FROM` until frog pins the verified value.
`docker_build.sh` passes the pinned value as
`--build-arg RUNTIME_BASE_IMAGE_REF=python:<tag>@sha256:<64 lowercase hex>`
(env `BASE_IMAGE_DIGEST`, validated anchored/lowercase-only) and refuses to
treat placeholder tokens or malformed digests as verified values. ONLY a dev
structural build behind the explicit `AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD`
switch may FROM the bare base tag instead — such a build is marked
`releasable=false`.

## 2. Runtime packages live in a lockfile, not inline Dockerfile text

Spec §12 line 1305: 四个现有 Python 包版本从 Dockerfile 内联文字移到锁文件。

The four packages previously inlined as a `PKGS` literal in
`Dockerfile.runtime` were moved **verbatim** (no version changes) into
`requirements-image.lock`:

| package    | version |
|------------|---------|
| numpy      | 2.4.1   |
| pandas     | 2.3.3   |
| matplotlib | 3.10.8  |
| scipy      | 1.17.0  |

`lockDigest = sha256:<hex>` of the lockfile bytes is computed by
`docker_build.sh` (via `sha256sum`/`shasum`) and recorded in the manifest,
the OCI labels and the external mapping.

`alphafrog_finance` does not appear in the lockfile because it is not
installed in the current runtime image. Spec §12's example shows it with
`apiVersion "1.0"`, and `build_runtime_manifest.py` supports an optional
`apiVersion` per package; adding the package (and its real version) is a
release decision for frog — agents never fabricate package versions.

## 3. Build-time manifest: library-set.json, librarySetDigest, labels

Spec §12 line 1306: 构建期生成 `library-set.json`、`librarySetDigest` 和 SBOM，
并把摘要写入 OCI 镜像标签。

`docker_build.sh runtime` (before invoking `docker build`):

1. computes `lockDigest` from the lockfile;
2. assembles the packages JSON array from the lockfile (name/version entries);
3. invokes `scripts/build_runtime_manifest.py` to write
   `.runtime-build/library-set.json` and reads back `librarySetDigest`.

`library-set.json` shape (Spec §12 lines 1324–1338): `schemaVersion "1"`,
`lockDigest`, `methodSpecIndexDigest`, `packages` sorted by name and
validated fail-closed against the exact allowlist `name`/`version`/optional
`apiVersion`, and `librarySetDigest`. Unknown package fields are NEVER
silently dropped; `name`/`version` are required non-empty strings;
`apiVersion` must be non-empty when present; package names must be unique.
Violations raise an error naming the offending package and field.

`librarySetDigest` is fully specified (Spec §12 line 1358; implemented in
`library_set_digest()`): sort the package array by `name`, serialise with
canonical JSON (sorted object keys, compact `(",", ":")` separators,
`ensure_ascii=False`), encode UTF-8, sha256, render as `sha256:<hex>`.

`Dockerfile.runtime` then bakes the same manifest into the image at
`/opt/alphafrog/runtime/library-set.json` by running the same script with the
same inputs (`AF_LOCK_DIGEST`, `AF_METHOD_SPEC_INDEX_DIGEST` build args;
packages re-derived from the lockfile), so the in-image file and the
host-side `librarySetDigest` agree by construction. Missing attestation
build args fail the image build loudly.

OCI labels written by `Dockerfile.runtime`:

| label | value |
|-------|-------|
| `com.alphafrog.runtime` | `true` (marks managed runtime images for retention) |
| `com.alphafrog.schemaVersion` | `1` |
| `com.alphafrog.lockDigest` | lockfile sha256 |
| `com.alphafrog.librarySetDigest` | precomputed librarySetDigest |
| `com.alphafrog.methodSpecIndexDigest` | MethodSpec index digest |
| `com.alphafrog.baseImageDigest` | verified base-image digest |

`sbomDigest` is intentionally **not** a label: the SBOM is generated from the
finished image, so its digest can only be recorded in the external mapping —
writing it back into the image would require a rebuild (self-reference).

## 4. Immutable image ID and the external digest mapping

Spec §12 line 1307: `docker build --iidfile` 取得不可变 image ID；镜像外生成
`imageDigest → lockDigest/librarySetDigest/sbomDigest/build revision` 对照文件，
避免把最终镜像摘要写回镜像造成自引用。

`docker_build.sh` builds with `--iidfile .runtime-build/image-id`, then —
only after the image ID is known — invokes `build_runtime_manifest.py` a
second time with `--mapping-output`/`--image-digest` to write
`.runtime-build/image-digest-mapping.json` (Spec §12 lines 1340–1356):
`{"schemaVersion":"1","images":{"<imageDigest>":{baseImageDigest, lockDigest,
librarySetDigest, sbomDigest, methodSpecIndexDigest, buildRevision,
releasable, incompleteInputs}}}`.
`build_external_digest_mapping()` never mutates image labels (pinned by
tests); the mapping lives outside the image and is consumed by deploy config
and audit queries.

Immutable same-origin evidence rule: the mapping KEY must be EXACTLY the
`sha256:<64 lowercase hex>` image ID from the phase-2 `--iidfile`, and the
recorded `imageRef` (a NON-evidence alias) may only be that same immutable
ID or an anchored `repo@sha256:<64hex>` digest reference. A MUTABLE tag
(e.g. `alphafrog-sandbox-runtime:latest`) is never digest evidence —
`build_external_digest_mapping()` rejects such values fail-closed. The local
convenience tag on the phase-2 `docker build -t` stays an alias only: it
never enters the SBOM, the mapping, or the deploy chain.

Release gate: every entry carries `releasable`. `docker_build.sh` passes
`--incomplete-input NAME` for each release input that was missing or a
`REPLACE_WITH_...` placeholder (`BASE_IMAGE_DIGEST`,
`METHOD_SPEC_INDEX_DIGEST`, `SBOM_DIGEST`); any incomplete input forces
`releasable=false`. `deploy_latest.sh` refuses to deploy when the mapping is
absent-`releasable`, `releasable=false`, or unparseable (fail-closed),
unless the explicit `AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD` switch is set.

`buildRevision` is `git:<commit>` of the checkout (empty outside git).

### SBOM (optional hook)

If `syft` is on PATH, `docker_build.sh` scans EXACTLY THIS build's immutable
image ID read from the phase-2 `--iidfile` (`syft "docker:<iidfile-ID>" -o
json`) and records the sha256 of the SBOM document as `sbomDigest`. The
mutable `:latest` tag is NEVER the scan target: between phase-2 completion
and the syft read the tag can be retargeted by another concurrent/manual
build, so a tag-based scan could attribute a DIFFERENT image's SBOM to this
build's exact imageDigest (Spec §12 immutable same-origin; pinned by the
fake-docker/fake-syft drift regressions in
`tests/test_runtime_image_retention.py`). If `syft` is absent or fails,
`sbomDigest` stays the explicit placeholder
`REPLACE_WITH_VERIFIED_SBOM_DIGEST` — never a fabricated digest — and the
SBOM input counts as incomplete for the release gate: the build fails closed
unless the explicit dev switch is set, and then the mapping is marked
`releasable=false`. frog produces the verified SBOM at release time.

`methodSpecIndexDigest` is NOT a release input: `docker_build.sh` COMPUTES
it from the canonical `index.json` bytes (a hard build material) after the
five-file canonical gate, so it can never be missing once the gate passes.
An optionally supplied `METHOD_SPEC_INDEX_DIGEST` env value is a
cross-check only and must equal the computed digest or the build fails
closed. Malformed (non-`sha256:<64 lowercase hex>`) values are hard errors
in every case.

## 5. AF_SANDBOX_IMAGE: digest reference required in production

Spec §12 line 1308: 生产 `AF_SANDBOX_IMAGE` 必须是摘要引用；开发环境是否允许标签
必须有独立开关，不能在生产静默退回 `latest`。

Implemented in `app/config.py` (`load_config`, stdlib only) with semantics
identical to `scripts/build_runtime_manifest.py::validate_af_sandbox_image`
(scripts/ is not an importable package, so the check is mirrored):

- `AF_SANDBOX_IMAGE` has **no implicit default**; the pre-§12 silent fallback
  to `alphafrog-sandbox-runtime:latest` is removed. Unset/empty → startup
  fails with a clear error.
- A digest reference is EXACTLY `<repo>@sha256:<64 lowercase hex>`: anchored
  full match over the entire string, lowercase-only hex. Uppercase hex, 63/65
  hex chars, a missing `@sha256:`, trailing extra hex or `:latest`, leading/
  trailing whitespace, and empty values are all rejected. The identical
  semantics are implemented in `scripts/build_runtime_manifest.py`,
  `deploy_latest.sh` and `docker_build.sh` (shared shell validator
  `scripts/af_digest_reference.sh`) and pinned by the shared accept/reject
  vectors in `tests/digest_reference_vectors.py`.
- A bare tag is rejected unless the independent, explicit switch
  `AF_SANDBOX_IMAGE_ALLOW_DEV_TAG` is exactly `true` or `1`
  (case-insensitive). The switch is for development only.
- Violations raise at config-load time; there is no silent `latest` fallback.

## 6. Image retention: plan by default, `--apply` deletes

Spec §12 lines 1309–1310: 保留脚本默认只显示计划，显式 `--apply` 才删除。只处理带
`com.alphafrog.runtime=true` 标签的镜像，保护当前、上一代、`state.json` 中
QUEUED/RUNNING 任务引用和 Docker 正在使用的镜像，不碰未知镜像。

`scripts/prune_runtime_images.sh`:

- **PLAN mode by default**: prints `would remove: ...` lines and never
  invokes `docker rmi`. Only `--apply` deletes.
- **Candidacy**: only images whose `docker inspect` labels contain
  `com.alphafrog.runtime=true` are candidates. Images without the label, or
  whose inspect fails, are unknown and untouchable.
- **Protection set** (never removed):
  - `$AF_CURRENT_RUNTIME_IMAGE` (current production image);
  - `$AF_PREVIOUS_RUNTIME_IMAGE` (previous generation);
  - `runtime_image_ref` of every `QUEUED`/`RUNNING` task in `$AF_STATE_FILE`
    (the task store JSON, `{"tasks": {...}}`). The durable schema
    `sandbox_task_store_v2` freezes the per-task image reference as the
    snake_case key `runtime_image_ref`; refs referenced ONLY by terminal
    tasks (`SUCCEEDED`/`FAILED`/`CANCELED`) are NOT protected, and legacy
    `sandbox_task_store_v1` records (no `runtime_image_ref`) are tolerated
    without crashing and without gaining extra protection;
  - images in use by running containers (per `docker ps`).
- Deletion set = runtime candidates minus protection set. JSON parsing uses
  python3 stdlib only (no jq); the script is bash-3.2 compatible.

## 7. Deployment wiring

- `docker-compose.yml` (`python-sandbox-service`): `AF_SANDBOX_IMAGE` plus
  `AF_SANDBOX_IMAGE_ALLOW_DEV_TAG` (empty default) and the retention
  protection-set vars `AF_CURRENT_RUNTIME_IMAGE`, `AF_PREVIOUS_RUNTIME_IMAGE`,
  `AF_STATE_FILE` — see comments in the compose file.
- `docker.env.example`: same variables with safe defaults and comments, plus
  the build/deploy-time release-gate escape hatch
  `AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD` (empty default; never implicit).
- `deploy_latest.sh`: before deploying the sandbox service/runtime image it
  verifies that `AF_SANDBOX_IMAGE` is an anchored, lowercase-only digest
  reference (bare tags only with the explicit dev switch) and that the build
  artifact `.runtime-build/image-digest-mapping.json` is releasable
  (fail-closed: a missing/unparseable `releasable` flag refuses the deploy;
  the only escape hatch is the explicit
  `AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD` switch). After deployment it runs
  `prune_runtime_images.sh` in PLAN mode. Actual deletion is frog's explicit
  act: `bash pythonSandboxService/scripts/prune_runtime_images.sh --apply`.

## 8. Release responsibilities

- Agents never execute `origin` pushes; image release and production config
  changes remain frog's final decision (Spec §12 line 1311).
- frog pins, at release time: the verified base-image digest, the real SBOM
  digest, the production `AF_SANDBOX_IMAGE` digest reference, and the
  retention protection set (`AF_CURRENT_RUNTIME_IMAGE` /
  `AF_PREVIOUS_RUNTIME_IMAGE`).
- image, library, SBOM, lock, MethodSpec index and build revision are
  build/deploy/audit facts; they do not enter the success-model projection or
  the user-facing three-column result block (Spec §12 line 1360).

## 9. Round-2 hardening (R2-1 … R2-4)

Round 2 supersedes the round-1 descriptions above wherever they differ
(notably §3's lockfile inference of library-set.json and §5's bare-tag dev
switch semantics).

### R2-1 — real alphafrog_finance install + smoke gate

`pythonSandboxService/runtime/` is the work-package-B distribution, imported
byte-exact (no merge of the B branch history). `docker_build.sh runtime` is a
TWO-PHASE build:

1. canonical inputs gate: work package A's canonical generated JSON
   (`METHOD_SPEC_CANONICAL_DIR`, default
   `agentToolsShared/target/generated-resources/finance/method-specs/v1`)
   must exist and contain `resolver-catalog.json` plus the three frozen
   method specs — a HARD build material; missing inputs fail closed with NO
   dev-switch escape;
2. `runtime/scripts/generate_method_bindings.py` runs host-side BEFORE the
   build and writes
   `runtime/src/alphafrog_finance/_generated/method_specs.json` (gitignored
   build product; hand-copied triples are forbidden, Spec §6). Generator
   failure aborts the build;
3. PHASE 1 (`--target runtime-install`, `--iidfile`): installs the lock set,
   then pip-installs the REAL distribution from `runtime/`;
4. SMOKE GATE: `scripts/smoke_runtime_image.py` runs via `docker run` against
   the phase-1 image under BOTH interpreters (system python and
   `/sandbox/.sandbox-venv/bin/python`): import `alphafrog_finance`,
   `__version__ == "1.0.0"`, `__api_version__ == "1.0"`, and the three
   frozen bindings VERBATIM (`finance.growth.cagr` /
   `finance.risk.annualized_volatility` / `finance.risk.sharpe_ratio`, each
   methodVersion `1.0.0`, specDigests
   `sha256:cff05d88…`, `sha256:2843745f…`, `sha256:fccc1f0f…`). Any failure
   aborts the build.

### R2-2 — verified ACTUAL inventory replaces lockfile inference

The round-1 `packages_json_from_lock()` inference and the "future lockfile
optional apiVersion" placeholder design are REMOVED. After phase 1,
`scripts/runtime_image_inventory.py --print-json` queries the image's ACTUAL
executing interpreter via `docker run` (PEP 503 names + real versions of
every installed distribution, incl. transitive deps, +
`alphafrog_finance.__api_version__`); the host-side `--verify` compares
fail-closed against the expected set (lock pins + alphafrog_finance
version/apiVersion from the runtime source): missing/extra managed package,
version or apiVersion mismatch aborts the build. The verified inventory feeds
`library-set.json`, the OCI `librarySetDigest` label (PHASE 2 bakes both,
FROM the phase-1 immutable ID) and the external mapping — all three carry the
SAME verified set.

### R2-3 — deploy target binding

`deploy_latest.sh` refuses to deploy unless the deploy target is PROVABLY the
built image: the mapping file must exist and parse; the chosen
`AF_SANDBOX_IMAGE` must resolve via `docker inspect` to an immutable image
ID; EXACTLY ONE mapping entry corresponds to the target (entry key ==
inspected ID, or entry `imageRef` == chosen ref); that entry's key must equal
the inspected ID; and its base/lock/library/SBOM/MethodSpec digests must all
be legal non-placeholder `sha256:<64 lowercase hex>` with `releasable=true`.
Only the `releasable` verdict is relaxable (explicit
`AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD`); target binding never is.

### R2-4 — no implicit image default; dev switch admits only valid refs

`docker-compose.yml` passes `AF_SANDBOX_IMAGE` through with NO default
(`${AF_SANDBOX_IMAGE:-}`); `docker.env.example` documents only commented
examples. The dev-allow switch admits ONLY syntactically VALID bare
tag/references (`is_valid_dev_reference` / `af_is_valid_dev_reference`):
empty values, whitespace/control characters, wrong digest lengths, uppercase
digests and garbage are rejected with the switch ON as well as OFF, and
anything digest-shaped must satisfy the anchored lowercase digest grammar
EVEN UNDER the switch. The shared vector sets `VALID_DEV_REFERENCES` /
`MALFORMED_UNDER_DEV_REFS` in `tests/digest_reference_vectors.py` pin all
three surfaces (config / manifest / deploy).

## 7. 260814 scheduler-03: verify-mode selection (local-image-id vs strict-release)

`AF_SANDBOX_IMAGE_VERIFY_MODE` selects which image reference contract
applies; accepted values are `local-image-id` (default) and `strict-release`.
The two modes are independent — enabling one never silently re-enables the
other's escape hatches.

### local-image-id（默认，单机正式模式）

- `AF_SANDBOX_IMAGE` must BE the local Image ID: exactly `sha256:<64
  lowercase hex>`, no repository prefix. Tags and repo digests are rejected;
  `AF_SANDBOX_IMAGE_ALLOW_DEV_TAG` does not apply in this mode (there is no
  dev-allow escape — local-image-id is itself the supported single-machine
  contract).
- The service (FastAPI lifespan) refuses to start unless the configured ID
  exists on the host, verified through the mounted Docker socket
  (`app/runtime_image_verify.py`). Missing image, unreachable socket or any
  query failure fails CLOSED.
- Optional `AF_SANDBOX_IMAGE_TAG_CHECK` (e.g.
  `alphafrog-sandbox-runtime:latest`): resolved exactly ONCE at startup and
  must point to the SAME Image ID. Task creation never re-resolves the tag;
  the frozen ref comes from `AF_SANDBOX_IMAGE` only.
- `docker_build.sh` in this mode runs the real gates (import checks, smoke
  gate, inventory gate) and prints the final immutable Image ID for deploy
  config; the strict-release inputs (base digest, SBOM, external mapping,
  Tier2a) are not build success conditions and the mapping is not written.
- `deploy_latest.sh` in this mode validates the ID shape and requires
  `docker inspect` to resolve to EXACTLY the configured ID (plus the optional
  tag check). The D15 mapping/Tier2a chain is skipped; the script never
  downgrades itself to tag mode.

### strict-release（未来仓库发布链，Spec §12）

The pre-existing digest-reference policy (anchored lowercase
`repo@sha256:<64hex>` + the explicit `AF_SANDBOX_IMAGE_ALLOW_DEV_TAG`
switch) and the full D15 registry digest / SBOM / mapping / Tier2a chain
remain unchanged, selected explicitly with
`AF_SANDBOX_IMAGE_VERIFY_MODE=strict-release`. `AF_SANDBOX_IMAGE_TAG_CHECK`
is rejected in this mode.

No real Docker daemon, image or container is required by the unit tests:
config vectors, the verify function (injectable docker client) and the
build/deploy script gates are pinned with fake docker fixtures
(`tests/test_config_image_mode.py`, `tests/test_runtime_image_verify.py`,
`tests/test_runtime_image_retention.py` local-mode classes).
