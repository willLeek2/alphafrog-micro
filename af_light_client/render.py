from __future__ import annotations

import shutil
import sys
import os
from typing import Iterable, List, Optional

from .events import ViewSnapshot


class TerminalRenderer:
    def __init__(self, *, max_lines: int = 14, color: Optional[bool] = None) -> None:
        self.max_lines = max(1, int(max_lines))
        auto_color = sys.stdout.isatty() and "NO_COLOR" not in os.environ
        self.color = auto_color if color is None else bool(color)
        self._started = False

    def render(self, snapshot: ViewSnapshot) -> None:
        self._started = True
        lines = self._build_lines(snapshot)
        sys.stdout.write("\x1b[?25l\x1b[H\x1b[J")
        sys.stdout.write("\n".join(lines))
        sys.stdout.write("\n")
        sys.stdout.flush()

    def finish(self, snapshot: ViewSnapshot) -> None:
        self.render(snapshot)
        sys.stdout.write("\x1b[?25h")
        sys.stdout.flush()

    def snapshot_text(self, snapshot: ViewSnapshot) -> str:
        return "\n".join(self._build_lines(snapshot)) + "\n"

    def _build_lines(self, snapshot: ViewSnapshot) -> List[str]:
        width = max(72, min(120, shutil.get_terminal_size((100, 32)).columns))
        title = f"AlphaFrog Agent  {snapshot.status}"
        if snapshot.phase:
            title += f" / {snapshot.phase}"
        title += f"  seq={snapshot.last_seq}  workflow={snapshot.workflow}"
        if snapshot.run_id:
            title += f"  run={snapshot.run_id[:12]}"
        lines = [title[:width], "─" * min(width, len(title) if len(title) > 72 else width)]
        if snapshot.workflow == "dag" and snapshot.dag_nodes:
            lines.extend(self._dag_lines(snapshot, width))
        else:
            lines.extend(self._linear_lines(snapshot, width))
        lines.extend(self._warning_box(snapshot.warnings, width))
        if snapshot.final_answer:
            lines.append("")
            lines.extend(self._final_box(snapshot.final_answer, width))
        return lines

    def _linear_lines(self, snapshot: ViewSnapshot, width: int) -> List[str]:
        if not snapshot.trace:
            return ["◦ waiting for run events..."]
        out: List[str] = []
        visible = snapshot.trace[-self.max_lines :]
        for idx, line in enumerate(visible):
            marker = "▶" if idx == len(visible) - 1 and snapshot.status.upper() not in {"COMPLETED", "FAILED"} else "•"
            wrapped = _wrap(line, max(20, width - 2), limit_lines=2)
            for wrapped_idx, wrapped_line in enumerate(wrapped):
                prefix = f"{self._marker(marker)} " if wrapped_idx == 0 else "  "
                out.append(prefix + self._line(wrapped_line))
        return out

    def _dag_lines(self, snapshot: ViewSnapshot, width: int) -> List[str]:
        out: List[str] = []
        for node_id, node_lines in snapshot.dag_nodes.items():
            header = f" DAG Node {node_id} "
            out.append(header.center(min(width, 72), "─"))
            for idx, line in enumerate(node_lines[-3:]):
                marker = "▶" if idx == len(node_lines[-3:]) - 1 else "•"
                wrapped = _wrap(line, max(20, width - 2), limit_lines=2)
                for wrapped_idx, wrapped_line in enumerate(wrapped):
                    prefix = f"{self._marker(marker)} " if wrapped_idx == 0 else "  "
                    out.append(prefix + self._line(wrapped_line))
        return out or ["◦ waiting for DAG node events..."]

    def _warning_box(self, warnings: Iterable[str], width: int) -> List[str]:
        body = list(warnings)
        box_width = min(width, 96)
        lines = ["", self._warn("┌" + "─" * (box_width - 2) + "┐")]
        title = " 运行 warnings "
        lines.append(self._warn("│" + title.ljust(box_width - 2) + "│"))
        if body:
            for warning in body:
                for wrapped in _wrap(warning, box_width - 4, limit_lines=3):
                    lines.append(self._warn("│ ") + wrapped.ljust(box_width - 4) + self._warn(" │"))
        else:
            lines.append(self._warn("│ ") + "暂无".ljust(box_width - 4) + self._warn(" │"))
        lines.append(self._warn("└" + "─" * (box_width - 2) + "┘"))
        return lines

    def _final_box(self, answer: str, width: int) -> List[str]:
        box_width = min(width, 96)
        lines = [self._success("┌" + "─" * (box_width - 2) + "┐")]
        lines.append(self._success("│ Final".ljust(box_width - 1) + "│"))
        for wrapped in _wrap(answer, box_width - 4, limit_lines=12, collapse_lines=False):
            lines.append(self._success("│ ") + wrapped.ljust(box_width - 4) + self._success(" │"))
        lines.append(self._success("└" + "─" * (box_width - 2) + "┘"))
        return lines

    def _marker(self, marker: str) -> str:
        if not self.color:
            return marker
        color = "\x1b[36m" if marker == "▶" else "\x1b[32m"
        return f"{color}{marker}\x1b[0m"

    def _warn(self, text: str) -> str:
        if not self.color:
            return text
        return f"\x1b[33m{text}\x1b[0m"

    def _success(self, text: str) -> str:
        if not self.color:
            return text
        return f"\x1b[32m{text}\x1b[0m"

    def _line(self, text: str) -> str:
        if not self.color:
            return text
        lower = text.lower()
        if "失败" in text or "failed" in lower or "error" in lower:
            return f"\x1b[31m{text}\x1b[0m"
        if "完成" in text or "finished" in lower or "completed" in lower:
            return f"\x1b[32m{text}\x1b[0m"
        if "开始" in text or "进行中" in text or "started" in lower:
            return f"\x1b[36m{text}\x1b[0m"
        return text


def _wrap(text: str, width: int, *, limit_lines: int, collapse_lines: bool = True) -> List[str]:
    raw_text = str(text)
    if collapse_lines:
        chunks = [" ".join(raw_text.splitlines())]
    else:
        chunks = [line.strip() for line in raw_text.splitlines() if line.strip()]
    if not chunks:
        return [""]
    lines: List[str] = []
    truncated = False
    for chunk in chunks:
        current = chunk
        while current and len(lines) < limit_lines:
            if len(current) <= width:
                lines.append(current)
                current = ""
                break
            cut = current.rfind(" ", 0, width)
            if cut <= 0:
                cut = width
            lines.append(current[:cut])
            current = current[cut:].lstrip()
        if current:
            truncated = True
            break
        if len(lines) >= limit_lines:
            if chunk != chunks[-1]:
                truncated = True
            break
    if truncated and lines:
        lines[-1] = lines[-1][: max(0, width - 1)] + "…"
    return lines
