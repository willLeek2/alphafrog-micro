"""TitleMatchFilter unit tests.

No DB / HTTP / external services.
"""
import os
import sys

import pytest

_RAG_DIR = os.path.dirname(os.path.abspath(__file__))
if _RAG_DIR not in sys.path:
    sys.path.insert(0, _RAG_DIR)

from title_match_filter import TitleMatchFilter, normalize_title_filters
from ts_code_filter import ConfigError


