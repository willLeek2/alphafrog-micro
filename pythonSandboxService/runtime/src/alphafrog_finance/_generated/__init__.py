# === work-package-B (ccqwen) ===
"""Build-product container for alphafrog_finance generated bindings (Spec §6).

This package exists only to give the generated build products
(``method_specs.json``, ``docstrings.py``, ``call_samples.py``) a stable,
importable location. Its contents (except this ``__init__.py``) are produced
by ``runtime/scripts/generate_method_bindings.py`` at image-build time and are
never committed — hand-copied method identity is forbidden.
"""
