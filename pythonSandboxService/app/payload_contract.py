"""D15 §4.2.3 round-4 (codex 56976668 MUST-FIX #3): single payload contract
shared by both the runtime parser (``bounded_exec_wrapper.parse_wrapper_input``)
and the pydantic model (``models.BoundedExecRequest``).

Lives in its own module so the wrapper keeps its stdlib-only invariant (no
pydantic import) while the model layer can re-import the same functions
without dragging pydantic into the wrapper's import graph. The wrapper is
invoked by the production runner as a standalone subprocess; pydantic being
a hard dep there would force staging pydantic into every task workspace.

Three classes of invariant live here:

1. **Field-level** (always checked, no filesystem context required):
   - ``taskEnvironment`` is a ``dict[str, str]`` with EXACTLY the four
     ``AF_TASK_*`` keys present and non-empty. Any other key — most
     importantly ``PYTHONPATH`` / ``PYTHONHOME`` / ``PYTHONSTARTUP`` — is
     rejected before spawn so a stale ``sitecustomize.py`` cannot be
     re-activated at child startup via a smuggled ``PYTHONPATH``.
   - ``AF_TASK_WORKSPACE`` equals ``taskWorkspace`` (both must be present).
   - ``AF_TASK_ARTIFACT_DIR`` / ``AF_TASK_TMP_DIR`` / ``AF_TASK_METRICS_PATH``
     point STRICTLY BENEATH ``taskWorkspace`` (realpath-resolved on both
     sides; equal-to-workspace is rejected for these three).

2. **Filesystem-anchored** (only when ``wrapper_input_path`` is given):
   - ``taskWorkspace``'s realpath MUST equal ``wrapper_input_path``'s parent
     dir realpath. Production runner writes ``wrapper-input.json`` at
     ``{task_workspace}/wrapper-input.json`` so this is the natural anchor.
     Without it ``taskWorkspace`` could be ``/``, ``..``, parent dir, or a
     symlink to an external target, defeating every sub-path check.
   - ``scriptPath``'s realpath MUST live at-or-inside ``taskWorkspace``.

3. **Filesystem existence / type** (NOT done here — done in the wrapper
   parser, which has direct filesystem access): ``scriptPath`` must be a
   regular file; ``loaderPythonPath`` must exist and be a directory; the
   ``_bootstrap`` dir under ``taskWorkspace`` must not be a pre-planted
   symlink.

This module owns the CONTRACT; the wrapper adds filesystem evidence on
top. Both layers calling the same ``validate_payload_contract`` function
is what closes codex 56976668 MUST-FIX #3 ("model 与 runtime parser 要用
同一合同，不能只在'字段有没有'这一层对齐").
"""
from __future__ import annotations

import os
from typing import Optional

# D15 §4.2.3 round-4 (codex 56976668 MUST-FIX #1): the wrapper may only
# inject the four AF_TASK_* env keys into the user child. Any other key
# (PYTHONPATH / PYTHONHOME / PYTHONSTARTUP / unknown) is rejected before
# spawn so a stale sitecustomize.py cannot be re-activated at child
# startup via a smuggled PYTHONPATH. Frozen as a frozenset so a caller
# cannot mutate it at runtime.
ALLOWED_TASK_ENV_KEYS = frozenset({
    "AF_TASK_WORKSPACE",
    "AF_TASK_ARTIFACT_DIR",
    "AF_TASK_TMP_DIR",
    "AF_TASK_METRICS_PATH",
})

# Subset of unknown keys whose presence in taskEnvironment is known to
# re-activate the stale-sitecustomize attack vector. Used to give a
# sharper error message so an operator sees the actual security
# implication rather than a generic schema complaint.
_PYTHON_SITE_INIT_HAZARD_KEYS = frozenset({
    "PYTHONPATH",
    "PYTHONHOME",
    "PYTHONSTARTUP",
})


class PayloadContractError(ValueError):
    """Raised when a wrapper-input.json payload violates the D15 §4.2.3
    round-4 contract (env whitelist / containment / consistency)."""


def is_within(task_workspace: str, candidate: str) -> bool:
    """Realpath-resolved at-or-inside check (allows EQUAL).

    Returns True iff ``candidate``'s realpath is exactly ``task_workspace``'s
    realpath or lives strictly beneath it. Symlinks that escape via realpath
    resolution are rejected. ``task_workspace`` itself is accepted as the
    trivially-inside case (relevant for ``AF_TASK_WORKSPACE`` which must
    EQUAL ``taskWorkspace``).
    """
    if not task_workspace or not candidate:
        return False
    base = os.path.realpath(task_workspace)
    target = os.path.realpath(candidate)
    if target == base:
        return True
    # Strictly beneath: base + "/" + something. The trailing slash means a
    # sibling directory whose name is a prefix of base (e.g. /ws vs /ws-other)
    # is correctly rejected.
    return target.startswith(base.rstrip("/") + "/")


def is_strictly_within(task_workspace: str, candidate: str) -> bool:
    """Realpath-resolved strictly-beneath check (rejects EQUAL).

    Same as :func:`is_within` but rejects the equal case. Used for
    ``AF_TASK_ARTIFACT_DIR`` / ``AF_TASK_TMP_DIR`` / ``AF_TASK_METRICS_PATH``
    which must live STRICTLY beneath workspace (codex 56976668 round-4:
    "AF 子路径 ... 等于 workspace" is a violation).
    """
    if not task_workspace or not candidate:
        return False
    base = os.path.realpath(task_workspace)
    target = os.path.realpath(candidate)
    if target == base:
        return False
    return target.startswith(base.rstrip("/") + "/")


def validate_payload_contract(
    payload: dict, *, wrapper_input_path: Optional[str] = None
) -> None:
    """Validate a wrapper-input.json payload against the D15 §4.2.3 round-4
    contract. Single source of truth shared by:

      - ``bounded_exec_wrapper.parse_wrapper_input`` (runtime parser,
        filesystem-anchored; passes ``wrapper_input_path`` for the
        workspace-anchor + scriptPath-containment checks)
      - ``models.BoundedExecRequest`` (pydantic model_validator; field-level
        subset only since pydantic construction has no filesystem context)

    See the module docstring for the full list of invariants. Raises
    :class:`PayloadContractError` on any violation.
    """
    # === Field-level: taskEnvironment shape + whitelist + completeness ===
    task_env = payload.get("taskEnvironment")
    if not isinstance(task_env, dict):
        raise PayloadContractError(
            "taskEnvironment must be a JSON object of strings"
        )
    for key, value in task_env.items():
        if not isinstance(value, str):
            raise PayloadContractError(
                f"taskEnvironment.{key} must be a string"
            )
    extra_keys = set(task_env.keys()) - ALLOWED_TASK_ENV_KEYS
    if extra_keys:
        # Distinguish python site-init hazard keys from generic unknowns so
        # the operator sees the actual attack vector being closed (not a
        # generic schema complaint).
        hazards = extra_keys & _PYTHON_SITE_INIT_HAZARD_KEYS
        if hazards:
            raise PayloadContractError(
                "taskEnvironment contains PYTHON site-init hazard keys: "
                f"{sorted(hazards)} (D15 §4.2.3 round-4 codex 56976668 "
                f"MUST-FIX #1: PYTHONPATH/PYTHONHOME/PYTHONSTARTUP are "
                f"explicitly forbidden in taskEnvironment because they "
                f"re-activate the stale-sitecustomize attack vector the "
                f"round-2 bootstrap mode just closed; a smuggled "
                f"PYTHONPATH pointing at a directory with a stale "
                f"sitecustomize.py would auto-import it at Python startup "
                f"and override AF_TASK_*)"
            )
        raise PayloadContractError(
            "taskEnvironment contains non-allowlisted keys: "
            f"{sorted(extra_keys)} (D15 §4.2.3 round-4: only the four "
            f"AF_TASK_* keys are permitted in taskEnvironment; "
            f"PYTHONPATH/PYTHONHOME/PYTHONSTARTUP are explicitly forbidden "
            f"because they re-enable the stale-sitecustomize attack vector)"
        )
    missing_keys = ALLOWED_TASK_ENV_KEYS - set(task_env.keys())
    if missing_keys:
        raise PayloadContractError(
            "taskEnvironment is missing required keys: "
            f"{sorted(missing_keys)}"
        )
    blanks = [k for k in ALLOWED_TASK_ENV_KEYS if not task_env.get(k)]
    if blanks:
        raise PayloadContractError(
            f"taskEnvironment keys must be non-empty: {sorted(blanks)}"
        )

    # === Field-level: taskWorkspace non-empty string ===
    task_workspace = payload.get("taskWorkspace")
    if not isinstance(task_workspace, str) or not task_workspace:
        raise PayloadContractError(
            "taskWorkspace must be a non-empty string"
        )

    # === Field-level: AF_TASK_WORKSPACE == taskWorkspace ===
    af_workspace = task_env["AF_TASK_WORKSPACE"]
    if af_workspace != task_workspace:
        raise PayloadContractError(
            "taskEnvironment.AF_TASK_WORKSPACE must equal taskWorkspace "
            f"(AF_TASK_WORKSPACE={af_workspace!r}, "
            f"taskWorkspace={task_workspace!r}; D15 §4.2.3 round-4: a "
            f"payload whose workspace and env disagree is fail-closed)"
        )

    # === Field-level: AF_TASK_ARTIFACT_DIR / TMP_DIR / METRICS_PATH STRICTLY
    # beneath taskWorkspace (realpath-resolved). Equal-to-workspace is
    # rejected for these three (codex 56976668 round-4 MUST-FIX #2:
    # "AF 子路径 ... 等于 workspace" is a violation).
    for key in (
        "AF_TASK_ARTIFACT_DIR", "AF_TASK_TMP_DIR", "AF_TASK_METRICS_PATH"
    ):
        if not is_strictly_within(task_workspace, task_env[key]):
            raise PayloadContractError(
                f"taskEnvironment.{key}={task_env[key]!r} must point "
                f"STRICTLY BENEATH taskWorkspace={task_workspace!r} "
                f"(not equal to it; not a parent; not a sibling; not an "
                f"external path; D15 §4.2.3 round-4 codex 56976668 "
                f"MUST-FIX #2: a task-local path that escapes its "
                f"workspace is fail-closed)"
            )

    # === Filesystem-anchored: workspace == wrapper-input.json parent ===
    if wrapper_input_path is not None:
        wrapper_parent = os.path.realpath(
            os.path.dirname(os.path.abspath(wrapper_input_path))
        )
        ws_real = os.path.realpath(task_workspace)
        if ws_real != wrapper_parent:
            raise PayloadContractError(
                f"taskWorkspace={task_workspace!r} (realpath {ws_real!r}) "
                f"must equal the wrapper-input.json parent directory "
                f"(realpath {wrapper_parent!r}); D15 §4.2.3 round-4 codex "
                f"56976668 MUST-FIX #2 anchoring: without this, taskWorkspace "
                f"could be '/', '..', parent dir, or a symlink to an "
                f"external target, defeating every sub-path containment "
                f"check below it"
            )

        # === Filesystem-anchored: scriptPath at-or-inside workspace ===
        # scriptPath may EQUAL workspace? No — scriptPath is a file, not a
        # directory, so equal-to-workspace makes no sense. Use
        # is_strictly_within for consistency with AF sub-paths. is_within
        # would also work (the realpath equality can never hold for a file
        # vs a dir).
        script_path = payload.get("scriptPath")
        if isinstance(script_path, str) and script_path:
            if not is_strictly_within(task_workspace, script_path):
                raise PayloadContractError(
                    f"scriptPath={script_path!r} must point STRICTLY "
                    f"BENEATH taskWorkspace={task_workspace!r} (D15 §4.2.3 "
                    f"round-4 codex 56976668 MUST-FIX #2: a scriptPath "
                    f"outside the task workspace is fail-closed — without "
                    f"this, a smuggled absolute path or symlink could "
                    f"execute arbitrary code from outside the workspace)"
                )


__all__ = [
    "ALLOWED_TASK_ENV_KEYS",
    "PayloadContractError",
    "is_within",
    "is_strictly_within",
    "validate_payload_contract",
]
