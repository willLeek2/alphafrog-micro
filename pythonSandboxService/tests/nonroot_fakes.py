"""Shared test fakes for the non-root staging path (260818).

Production staging no longer calls llm-sandbox ``copy_to_runtime`` (it
execs chown as root); it hands a tar entry owned by the container user to
the docker container proxy's ``put_archive`` (see app/container_copy.py).
Test fakes therefore need a container object whose ``put_archive`` PARSES
the staged tar instead of extracting it — container destination paths in
these suites are literal container paths (``/sandbox/...``), not host
paths, so nothing is written to the test machine's filesystem.

The end-to-end fakes that DO need real host extraction (the wiring
suite's HostArchiveContainer) stay where they are; this module is the
recording-only variant shared by the loader / csv-materialize /
pool-scheduler / data-intense suites.
"""

from __future__ import annotations

import io
import tarfile

from app.container_copy import CONTAINER_IDENTITY_ATTR

FAKE_CONTAINER_UID = 10000
FAKE_CONTAINER_GID = 10001


class RecordingPutArchive:
    """Fake docker container proxy for the non-root copy path.

    ``put_archive(dest_dir, tar_bytes)`` parses each tar member and
    records ``(payload_bytes, dest_path)`` with ``dest_path`` joined as
    ``dest_dir/name`` — the same shape the old ``copy_to_runtime``
    recorders captured, so suite assertions keep working.

    Failure injection: tests may assign ``self.put_archive = boom`` on
    the instance (or subclass) to simulate container-side staging
    failures; the recording here stays out of the way.
    """

    def __init__(self) -> None:
        self.calls: list[tuple[bytes, str]] = []

    def put_archive(self, dest_dir: str, data: bytes) -> None:
        with tarfile.open(fileobj=io.BytesIO(data)) as tar:
            for member in tar.getmembers():
                if member.isfile():
                    payload = tar.extractfile(member).read()
                else:
                    payload = b""
                self.calls.append((payload, f"{dest_dir.rstrip('/')}/{member.name}"))


def prime_fake_session(session) -> None:
    """Equip a fake session for app.container_copy:

    * a recording ``container`` proxy (``put_archive``);
    * the cached container identity ``(10000, 10001)`` — the same
      attribute ``prime_container_identity`` installs on REAL sessions
      at creation time, so the in-container ``id -u``/``id -g`` lookup
      never runs in these suites.
    """
    session.container = RecordingPutArchive()
    setattr(
        session,
        CONTAINER_IDENTITY_ATTR,
        (FAKE_CONTAINER_UID, FAKE_CONTAINER_GID),
    )
