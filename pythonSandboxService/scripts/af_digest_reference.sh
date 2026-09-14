#!/usr/bin/env bash
# === work-package-H (ccqwen) ===
#
# Shared digest-reference validation for MethodSpec V5 Spec §12 (single
# source of truth for the shell entry points deploy_latest.sh and
# docker_build.sh; the Python entry points app/config.py and
# scripts/build_runtime_manifest.py implement IDENTICAL semantics and all
# four are pinned by the same accept/reject vectors in
# pythonSandboxService/tests/digest_reference_vectors.py).
#
# Semantics: a pinned runtime image ref must be EXACTLY
#   <repository>@sha256:<64 LOWERCASE hex>
# anchored at BOTH ends (no leading/trailing content, no trailing extra hex
# char, no ":latest") and lowercase-only in the digest hex. The repository
# grammar mirrors the conservative Docker reference grammar used by
# scripts/build_runtime_manifest.py: '/'-separated non-empty lowercase
# [a-z0-9] components with single '.'/'_'/'-' separators, and an optional
# leading registry host that may carry a numeric port only when a path
# component follows it.
#
# Sourced (never executed directly); bash 3.2 compatible, stdlib only
# (printf + grep -E). Functions return 0 for ACCEPT, 1 for REJECT and never
# print anything, so callers control all diagnostics.

# NOTE: grep is line-oriented, so a value containing a newline could match on
# one line alone; reject such values up front (the Python fullmatch grammar
# rejects them too: newlines are not in any character class).
_AF_NEWLINE='
'

# <path-component> per the conservative Docker reference grammar.
_AF_PATH_COMPONENT='[a-z0-9]+([._-][a-z0-9]+)*'

# Full anchored reference grammar (used with grep -E, so ^...$ anchors the
# ENTIRE value; {64} pins the exact lowercase-hex length).
_AF_DIGEST_REFERENCE_ERE="^(${_AF_PATH_COMPONENT}(:[0-9]+)?/)?${_AF_PATH_COMPONENT}(/${_AF_PATH_COMPONENT})*@sha256:[0-9a-f]{64}\$"

# Bare digest value grammar: exactly sha256:<64 lowercase hex>.
_AF_SHA256_VALUE_ERE='^sha256:[0-9a-f]{64}$'

# <tag> per the conservative Docker reference grammar.
_AF_TAG='[A-Za-z0-9_][A-Za-z0-9._-]{0,127}'

# Syntactically valid BARE tag/reference grammar (Spec §12 round-2 R2-4):
# <repo>[:<tag>] over the same conservative lowercase path grammar. No '@'
# anywhere -- anything digest-shaped must satisfy the anchored digest grammar
# EVEN UNDER the dev-allow switch (callers reject '@'-bearing values).
_AF_DEV_REFERENCE_ERE="^(${_AF_PATH_COMPONENT}(:[0-9]+)?/)?${_AF_PATH_COMPONENT}(/${_AF_PATH_COMPONENT})*(:${_AF_TAG})?\$"

# af_is_digest_reference VALUE
#   Returns 0 iff VALUE is EXACTLY <repository>@sha256:<64 lowercase hex>.
af_is_digest_reference() {
  local value="${1:-}"
  [ -n "$value" ] || return 1
  case "$value" in
    *"$_AF_NEWLINE"*) return 1 ;;
  esac
  printf '%s' "$value" | grep -Eq "$_AF_DIGEST_REFERENCE_ERE"
}

# af_is_sha256_digest VALUE
#   Returns 0 iff VALUE is EXACTLY sha256:<64 lowercase hex> (anchored both
#   ends, lowercase only).
af_is_sha256_digest() {
  local value="${1:-}"
  [ -n "$value" ] || return 1
  case "$value" in
    *"$_AF_NEWLINE"*) return 1 ;;
  esac
  printf '%s' "$value" | grep -Eq "$_AF_SHA256_VALUE_ERE"
}

# af_is_valid_dev_reference VALUE
#   Returns 0 iff VALUE is a syntactically VALID bare tag/reference
#   (<repo>[:<tag>], conservative lowercase grammar): the ONLY shape the
#   explicit dev-allow switch admits (Spec §12 round-2 R2-4). Empty values,
#   whitespace/control characters and malformed tags are rejected; '@'-bearing
#   (digest-shaped) values are rejected here EVEN UNDER the dev switch.
af_is_valid_dev_reference() {
  local value="${1:-}"
  [ -n "$value" ] || return 1
  case "$value" in
    *"$_AF_NEWLINE"*) return 1 ;;
    *"@"*) return 1 ;;
  esac
  printf '%s' "$value" | grep -Eq "$_AF_DEV_REFERENCE_ERE"
}
