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

    @staticmethod
    def _coerce_text(value: str | bytes | None) -> str:
        if value is None:
            return ""
        if isinstance(value, bytes):
            return value.decode("utf-8", errors="replace")
        return value

    @staticmethod
    def _trace_dir_with_files(run_cwd: Path, trace_dir: Any) -> str | None:
        if not isinstance(trace_dir, str) or not trace_dir:
            return None
        trace_path = run_cwd / trace_dir
        if trace_path.exists() and any(trace_path.iterdir()):
            return trace_dir
        return None

    @staticmethod
    def _try_parse_payload(stdout: str) -> dict[str, Any] | None:
        text = (stdout or "").strip()
        if not text:
            return None
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            return None
        return payload if isinstance(payload, dict) else None

    def run(self, request: dict[str, Any], timeout: int = 300, cwd: Path | str | None = None) -> BenchmarkAPIResponse:
        cmd = [*self.clj_cmd, "--request", "-", "--response", "-"]
        run_cwd = Path(cwd or self.project_root)
        payload_request = dict(request)
        try:
            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                cwd=str(run_cwd),
            )
            stdout, stderr = proc.communicate(input=json.dumps(payload_request), timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            stdout = self._coerce_text(exc.stdout)
            stderr = self._coerce_text(exc.stderr)
            if proc.poll() is None:
                try:
                    proc.terminate()
                except ProcessLookupError:
                    pass
            try:
                term_stdout, term_stderr = proc.communicate(timeout=5)
                stdout = stdout or self._coerce_text(term_stdout)
                stderr = stderr or self._coerce_text(term_stderr)
            except subprocess.TimeoutExpired as term_exc:
                stdout = stdout or self._coerce_text(term_exc.stdout)
                stderr = stderr or self._coerce_text(term_exc.stderr)
                if proc.poll() is None:
                    try:
                        proc.kill()
                    except ProcessLookupError:
                        pass
                kill_stdout, kill_stderr = proc.communicate()
                stdout = stdout or self._coerce_text(kill_stdout)
                stderr = stderr or self._coerce_text(kill_stderr)

            trace_dir = self._trace_dir_with_files(run_cwd, payload_request.get("trace_dir"))
            recovered_payload = self._try_parse_payload(stdout)
            payload = {
                **(recovered_payload or {}),
                "ok": False,
                "mode": payload_request.get("mode", "unknown"),
                "error": f"spell.benchmark-api timed out after {timeout}s",
                "error_type": "timeout",
                "error_data": {
                    "timeout_seconds": timeout,
                    "stdout": stdout[:4000],
                    "stderr": stderr[:4000],
                    "cmd": cmd,
                    "recovered_payload": recovered_payload,
                },
                "trace_dir": recovered_payload.get("trace_dir") if recovered_payload and recovered_payload.get("trace_dir") else trace_dir,
            }
            if recovered_payload is not None:
                payload["recovered_payload"] = recovered_payload
            return BenchmarkAPIResponse(
                ok=False,
                mode=str(payload["mode"]),
                result=payload.get("result"),
                usage=payload.get("usage"),
                latency_ms=payload.get("latency_ms"),
                error=payload["error"],
                error_type=payload["error_type"],
                error_data=payload["error_data"],
                trace_dir=payload["trace_dir"],
                raw=payload,
            )

        stdout = (stdout or "").strip()
        stderr = (stderr or "").strip()

        payload: dict[str, Any]
        if stdout:
            try:
                payload = json.loads(stdout)
            except json.JSONDecodeError as exc:
                payload = {
                    "ok": False,
                    "mode": payload_request.get("mode", "unknown"),
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
                "mode": payload_request.get("mode", "unknown"),
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
                "mode": payload.get("mode", payload_request.get("mode", "unknown")),
                "error": f"spell.benchmark-api exited with code {proc.returncode}",
                "error_type": "subprocess_failure",
                "error_data": {
                    "stderr": stderr[:4000],
                    "stdout": stdout[:4000],
                    "exit_code": proc.returncode,
                },
                "trace_dir": payload.get("trace_dir"),
            }

        if payload.get("trace_dir") is None:
            payload["trace_dir"] = self._trace_dir_with_files(run_cwd, payload_request.get("trace_dir"))

        return BenchmarkAPIResponse(
            ok=bool(payload.get("ok")),
            mode=str(payload.get("mode", payload_request.get("mode", "unknown"))),
            result=payload.get("result"),
            usage=payload.get("usage"),
            latency_ms=payload.get("latency_ms"),
            error=payload.get("error"),
            error_type=payload.get("error_type"),
            error_data=payload.get("error_data"),
            trace_dir=payload.get("trace_dir"),
            raw=payload,
        )
