# === work-package-C (ccqwen) ===
"""§7.1 step 7 capture readback: container-side artifact dump.

Runs INSIDE the execution container after the bounded wrapper finishes and
BEFORE task-workspace cleanup (``app.sandbox_runner`` invokes it via
``execute_command``)::

    <interpreter> .../bounded-wrapper/app/capture_reader.py <capture-dir>

It emits ONE JSON document on stdout::

    {"files": {"capture-result.json": "<base64>", "stdout.bin": "<base64>", ...}}

containing exactly the capture files that exist under ``<capture-dir>``,
base64-encoded (pure ASCII, safe to transport through the container exec
stdout channel).  File PRESENCE is significant: the host-side fail-closed
reader (``app.finance_record_channel.read_capture_artifacts``) validates
presence, byte lengths and record-channel consistency, so this module is a
deliberately dumb byte mover — it never interprets, truncates, filters or
fabricates artifacts, and an absent file is simply absent from the JSON.

The file-name whitelist is the §7.1 fixed capture layout; it is pinned to
``app.bounded_exec_wrapper``'s constants by
``tests/test_bounded_wrapper_wiring.py`` so the two sides cannot drift.

Stdlib only; never writes anything but the JSON document to stdout and, on
failure, a short diagnostic (type/message, never artifact CONTENT — §18) to
stderr with a non-zero exit code.
"""

from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

# §7.1 fixed capture layout (verbatim file names; keep in sync with
# app.bounded_exec_wrapper constants — pinned by the wiring tests).
CAPTURE_FILE_NAMES = (
    "capture-result.json",
    "stdout.bin",
    "stderr.bin",
    "finance-records.jsonl",
    "finance-records-unknown-marker.jsonl",
)


def read_capture_files(capture_dir: Path) -> dict:
    """Return ``{"files": {name: base64}}`` for every present capture file.

    Raises ``ValueError`` when ``capture_dir`` is not a directory: the
    wrapper always creates it before writing anything, so its absence means
    the capture itself never ran and the host must fail the task.
    """
    capture_path = Path(capture_dir)
    if not capture_path.is_dir():
        raise ValueError(f"capture directory missing: {capture_dir}")
    files: dict[str, str] = {}
    for name in CAPTURE_FILE_NAMES:
        path = capture_path / name
        if path.is_file():
            files[name] = base64.b64encode(path.read_bytes()).decode("ascii")
    return {"files": files}


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if len(args) != 1:
        sys.stderr.write("usage: capture_reader.py <capture-dir>\n")
        return 2
    try:
        document = read_capture_files(Path(args[0]))
    except (OSError, ValueError) as exc:
        sys.stderr.write(f"capture_reader: {exc}\n")
        return 1
    json.dump(document, sys.stdout)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
