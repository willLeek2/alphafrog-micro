"""Startup verification for the local-image-id image reference mode (260814 scheduler-03).

In ``local-image-id`` mode the service refuses to start unless the configured
``AF_SANDBOX_IMAGE`` (a bare ``sha256:<64hex>`` Image ID) really exists on the
host. The check goes through the mounted Docker socket; a missing image, an
unreachable socket or any query failure fails CLOSED -- the service must not
start with a reference it has not verified.

Optional cross-check: when ``AF_SANDBOX_IMAGE_TAG_CHECK`` is set (a mutable
tag such as ``alphafrog-sandbox-runtime:latest``), the tag is resolved exactly
once at startup and must point to the SAME local Image ID. Task creation never
re-resolves the tag afterwards (the frozen ref comes from config, see
main.create_task).

The verification function takes an injectable docker client so tests can use
a fake client without touching a real Docker daemon; ``build_docker_client``
is the thin production factory used by the lifespan hook.
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


class ImageVerificationError(RuntimeError):
    """Startup refusal: the configured image reference could not be verified."""


def build_docker_client():
    """Lazily build the Docker SDK client from the environment (DOCKER_HOST etc.).

    Imported lazily so environments without the docker SDK can still run
    config validation and unit tests that never call this factory.
    """
    import docker

    return docker.from_env()


def verify_local_image_id(image_id: str, *, client, tag_check: str = "") -> str:
    """Verify ``image_id`` exists on the host and (optionally) that ``tag_check``
    resolves to the same ID. Returns the frozen image ID on success; raises
    :class:`ImageVerificationError` on ANY failure (fail-closed).

    ``client`` is a docker SDK client (or any object exposing
    ``images.get(ref).id`` and raising ``docker.errors.ImageNotFound`` /
    ``docker.errors.DockerException`` on failure).
    """
    actual_id = _inspect_image_id(image_id, client, description="configured Image ID")
    if not tag_check:
        logger.info(
            "runtime image verified: local Image ID %s exists on this host",
            image_id,
        )
        return image_id

    tag_id = _inspect_image_id(tag_check, client, description="tag-check reference")
    if tag_id != actual_id:
        raise ImageVerificationError(
            "AF_SANDBOX_IMAGE_TAG_CHECK %r resolves to %s, but the configured "
            "Image ID is %s. The mutable tag no longer points at the verified "
            "image; refusing to start." % (tag_check, tag_id, image_id)
        )
    logger.info(
        "runtime image verified: local Image ID %s exists and tag %s resolves "
        "to the same ID",
        image_id,
        tag_check,
    )
    return image_id


def _inspect_image_id(ref: str, client, *, description: str) -> str:
    try:
        image = client.images.get(ref)
    except ImageVerificationError:
        raise
    except Exception as exc:
        # ImageNotFound (missing image), DockerException/APIError (socket
        # unreachable, daemon down) and any other failure all refuse startup.
        raise ImageVerificationError(
            "Failed to verify %s %r via the Docker socket: %s: %s. "
            "The service refuses to start with an unverified image reference."
            % (description, ref, type(exc).__name__, exc)
        ) from exc
    resolved = getattr(image, "id", None)
    if not resolved:
        raise ImageVerificationError(
            "Docker returned no Image ID for %s %r; refusing to start."
            % (description, ref)
        )
    return resolved.removeprefix("sha256:")
