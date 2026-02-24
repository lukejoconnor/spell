"""Python client for spell.benchmark-api JSON contract."""

from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(slots=True)
class BenchmarkAPIResponse:
    ok: bool
    mode: str
    result: str | None
    usage: dict[str, Any] | None
    latency_ms: float | None
    error: str | None
    error_type: str | None
    error_data: dict[str, Any] | None
    trace_dir: str | None
    raw: dict[str, Any]


class SpellBenchmarkClient:
    """Thin subprocess client for the Clojure benchmark API."""

    def __init__(self, project_root: Path | str | None = None, clj_cmd: list[str] | None = None):
        self.project_root = Path(project_root or Path(__file__).resolve().parent)
        self.clj_cmd = clj_cmd or ["clj", "-M", "-m", "spell.benchmark-api"]

    def run(self, request: dict[str, Any], timeout: int = 300, cwd: Path | str | None = None) -> BenchmarkAPIResponse:
        cmd = [*self.clj_cmd, "--request", "-", "--response", "-"]
        proc = subprocess.run(
            cmd,
            input=json.dumps(request),
            text=True,
            capture_output=True,
            timeout=timeout,
            cwd=str(cwd or self.project_root),
        )

        stdout = (proc.stdout or "").strip()
        stderr = (proc.stderr or "").strip()

        payload: dict[str, Any]
        if stdout:
            try:
                payload = json.loads(stdout)
            except json.JSONDecodeError as exc:
                payload = {
                    "ok": False,
                    "mode": request.get("mode", "unknown"),
                    "error": f"Invalid JSON from spell.benchmark-api: {exc}",
                    "error_type": "invalid_json_response",
                    "error_data": {
                        "stdout": stdout[:4000],
                        "stderr": stderr[:4000],
                        "exit_code": proc.returncode,
                    },
                }
        else:
            payload = {
                "ok": False,
                "mode": request.get("mode", "unknown"),
                "error": "No JSON response from spell.benchmark-api",
                "error_type": "missing_response",
                "error_data": {
                    "stderr": stderr[:4000],
                    "exit_code": proc.returncode,
                },
            }

        if proc.returncode != 0 and payload.get("ok") is True:
            payload = {
                "ok": False,
                "mode": payload.get("mode", request.get("mode", "unknown")),
                "error": f"spell.benchmark-api exited with code {proc.returncode}",
                "error_type": "subprocess_failure",
                "error_data": {
                    "stderr": stderr[:4000],
                    "stdout": stdout[:4000],
                    "exit_code": proc.returncode,
                },
            }

        return BenchmarkAPIResponse(
            ok=bool(payload.get("ok")),
            mode=str(payload.get("mode", request.get("mode", "unknown"))),
            result=payload.get("result"),
            usage=payload.get("usage"),
            latency_ms=payload.get("latency_ms"),
            error=payload.get("error"),
            error_type=payload.get("error_type"),
            error_data=payload.get("error_data"),
            trace_dir=payload.get("trace_dir"),
            raw=payload,
        )
