from __future__ import annotations

import threading
from collections import Counter


class Metrics:
    """Small dependency-free Prometheus exporter for the Agent service."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counters: Counter[tuple[str, tuple[tuple[str, str], ...]]] = Counter()

    def inc(self, name: str, **labels: str) -> None:
        key = (name, tuple(sorted((key, str(value)) for key, value in labels.items())))
        with self._lock:
            self._counters[key] += 1

    def render(self) -> str:
        lines = [
            "# HELP docpilot_agent_events_total Count of Agent runtime events.",
            "# TYPE docpilot_agent_events_total counter",
            "# HELP docpilot_agent_gateway_requests_total Count of deterministic tool gateway calls.",
            "# TYPE docpilot_agent_gateway_requests_total counter",
            "# HELP docpilot_agent_trace_exports_total Count of AgentOps trace export attempts.",
            "# TYPE docpilot_agent_trace_exports_total counter",
        ]
        with self._lock:
            items = sorted(self._counters.items())
        for (name, labels), value in items:
            label_text = ""
            if labels:
                label_text = "{" + ",".join(
                    f'{key}="{_escape(value)}"' for key, value in labels
                ) + "}"
            lines.append(f"{name}{label_text} {value}")
        return "\n".join(lines) + "\n"


def _escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")


metrics = Metrics()
