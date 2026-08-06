#!/usr/bin/env python3
"""Prepare and supervise the six precommitted Agentic Palisade OMP runs."""

import argparse
from concurrent.futures import CancelledError, ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import http.client
import http.server
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import struct
import secrets
import subprocess
import sys
import tempfile
import time
import threading
import urllib.parse
import uuid


SCRIPT_ROOT = Path(__file__).resolve().parent
BENCHMARK_ROOT = SCRIPT_ROOT.parent
REPOSITORY_ROOT = BENCHMARK_ROOT.parent.parent
FIXED_MODEL = "openai-codex/gpt-5.6-sol:medium"
# Authoritative image capability (`omp models` images column), mirrored from the
# PROFILES modelImagesRequired contract in release-gate.py; the runner stays
# standalone, so the two profile names and this capability map are duplicated
# here as the runner's small local constant set.
MODEL_IMAGE_CAPABLE = {
    "deepseek/deepseek-v4-flash": False,
    "deepseek/deepseek-v4-pro": False,
    "openai-codex/gpt-5.6-sol:medium": True,
    "openai-codex/gpt-5.6-luna:medium": True,
    "gitlab-duo/claude-sonnet-4-5-20250929": True,
    "gitlab-duo/claude-haiku-4-5-20251001": True,
}
FIXED_REASONING = "medium"
FIXED_BROKER_URL = "http://127.0.0.1:9000"
PROTOCOL_AMENDMENT = "agentic-palisade/task-8-auth-broker-amendment-v1"
FIXED_PAIRS = 3
RELEASE_PAIRS = 5
FIXED_ROUNDS = 3
FIXED_SECONDS = 45 * 60
MIN_SECONDS = 10 * 60
QUALIFICATION_SECONDS = 1
QUALIFICATION_OMP = BENCHMARK_ROOT / "fixtures/mock-omp.py"
TOOL_ALLOWLIST = "read,write,edit,bash,grep,glob"
GENERATED_NAMES = {".gradle", "build", "__pycache__"}
CANDIDATE_INPUT_NAMES = {"INSTRUCTIONS.md", "PROTOCOL.md", "corpus"}
DURATION = re.compile(r"(?P<amount>[1-9][0-9]*)(?P<unit>ms|s|m|h)\Z")
CANDIDATE_VERSION = re.compile(
    r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?\Z")
CANDIDATE_MODULES = (
    "harness-core", "harness-scene2d", "harness-lwjgl3",
    "harness-protocol", "harness-mcp",
)
LOCAL_DISPLAY = re.compile(r":(?P<number>[1-9][0-9]*)\Z")
CREDENTIAL_KEYS = {
    "SSH_AUTH_SOCK", "GIT_ASKPASS", "SSH_ASKPASS", "AWS_PROFILE",
    "AWS_SHARED_CREDENTIALS_FILE", "GOOGLE_APPLICATION_CREDENTIALS",
    "DOCKER_CONFIG", "KUBECONFIG", "NETRC",
}
SAFE_PARENT_ENV = {
    "JAVA_HOME", "JDK_HOME", "LANG", "LC_ALL", "LC_CTYPE", "LD_LIBRARY_PATH",
    "PATH", "SHELL", "TERM", "TZ",
}
BROKER_OWNER_ENV = SAFE_PARENT_ENV | {
    "HOME", "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME",
    "XDG_STATE_HOME",
}

AUTH_PREFLIGHT_SECONDS = 180

def _load_telemetry_module():
    spec = importlib.util.spec_from_file_location(
        "agentic_palisade_parse_omp_session", SCRIPT_ROOT / "parse-omp-session.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


TELEMETRY = _load_telemetry_module()


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def sha256_bytes(content):
    return hashlib.sha256(content).hexdigest()


def hash_tree(path, ignored_names=GENERATED_NAMES):
    path = Path(path)
    digest = hashlib.sha256()
    if not path.exists():
        raise FileNotFoundError(path)
    for item in sorted(path.rglob("*"), key=lambda candidate: candidate.relative_to(path).as_posix()):
        relative = item.relative_to(path)
        if any(part in ignored_names for part in relative.parts):
            continue
        if item.is_symlink():
            raise ValueError(f"symlinks are not allowed in benchmark inputs: {item}")
        if not item.is_file():
            continue
        encoded = relative.as_posix().encode("utf-8")
        content = item.read_bytes()
        digest.update(len(encoded).to_bytes(8, "big"))
        digest.update(encoded)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def hash_candidate(workspace):
    workspace = Path(workspace)
    digest = hashlib.sha256()
    for item in sorted(workspace.rglob("*"), key=lambda candidate: candidate.relative_to(workspace).as_posix()):
        relative = item.relative_to(workspace)
        if relative.parts and relative.parts[0] in CANDIDATE_INPUT_NAMES:
            continue
        if any(part in GENERATED_NAMES for part in relative.parts):
            continue
        if item.is_symlink():
            raise ValueError(f"candidate symlink is not allowed: {item}")
        if not item.is_file():
            continue
        encoded = relative.as_posix().encode("utf-8")
        content = item.read_bytes()
        digest.update(len(encoded).to_bytes(8, "big"))
        digest.update(encoded)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()

def verify_protected_inputs(workspace, expected_hashes):
    workspace = Path(workspace)
    checks = (
        ("INSTRUCTIONS.md", lambda: sha256_bytes((workspace / "INSTRUCTIONS.md").read_bytes()),
         expected_hashes.get("instructions")),
        ("PROTOCOL.md", lambda: sha256_bytes((workspace / "PROTOCOL.md").read_bytes()),
         expected_hashes.get("protocol")),
        ("corpus/", lambda: hash_tree(workspace / "corpus", ignored_names=frozenset()),
         expected_hashes.get("corpus")),
    )
    drift = []
    for name, observe, expected in checks:
        try:
            observed = observe()
        except (OSError, ValueError) as error:
            drift.append({"path": name, "message": str(error)})
            continue
        if expected is None or observed != expected:
            drift.append({
                "path": name,
                "message": f"expected {expected or 'missing manifest hash'}, observed {observed}",
            })
    overlay = workspace.parent / "treatments/harness"
    expected_overlay = expected_hashes.get("treatmentOverlay")
    if overlay.exists():
        try:
            observed_overlay = hash_tree(overlay, ignored_names=frozenset())
        except (OSError, ValueError) as error:
            drift.append({"path": "treatments/harness/", "message": str(error)})
        else:
            if observed_overlay != expected_overlay:
                drift.append({
                    "path": "treatments/harness/",
                    "message": (
                        f"expected {expected_overlay or 'absent overlay'}, "
                        f"observed {observed_overlay}"
                    ),
                })
    elif expected_overlay is not None:
        drift.append({"path": "treatments/harness/", "message": "overlay is missing"})
    return drift


def _make_read_only(path):
    path = Path(path)
    if path.is_dir():
        for item in path.rglob("*"):
            item.chmod(0o555 if item.is_dir() else 0o444)
        path.chmod(0o555)
    else:
        path.chmod(0o444)




def parse_duration(value):
    match = DURATION.fullmatch(value)
    if not match:
        raise argparse.ArgumentTypeError("duration must be a positive integer followed by ms, s, m, or h")
    amount = int(match.group("amount"))
    unit = match.group("unit")
    multiplier = {"ms": 0.001, "s": 1, "m": 60, "h": 3600}[unit]
    return amount * multiplier


def _write_exclusive_json(path, value, immutable=True):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(value, indent=2, sort_keys=True) + "\n"
    with path.open("x", encoding="utf-8") as stream:
        stream.write(serialized)
        stream.flush()
        os.fsync(stream.fileno())
    if immutable:
        path.chmod(0o444)


def _copy_tree(source, destination):
    shutil.copytree(
        source,
        destination,
        ignore=shutil.ignore_patterns(".gradle", "build", "__pycache__", "*.pyc"),
    )


def _treatment_inputs():
    baseline_path = BENCHMARK_ROOT / "treatments/baseline/INSTRUCTIONS.md"
    harness_path = BENCHMARK_ROOT / "treatments/harness/INSTRUCTIONS.md"
    marker = "## Treatment appendix\n"
    baseline = baseline_path.read_text(encoding="utf-8")
    harness = harness_path.read_text(encoding="utf-8")
    if baseline.count(marker) != 1 or harness.count(marker) != 1:
        raise ValueError("each treatment instruction must contain exactly one appendix marker")
    baseline_common, baseline_appendix = baseline.split(marker, 1)
    harness_common, harness_appendix = harness.split(marker, 1)
    if baseline_common != harness_common:
        raise ValueError("treatment instructions differ outside the approved appendix")
    return {
        "baseline": {
            "instructions": baseline,
            "appendixHash": sha256_bytes(baseline_appendix.encode("utf-8")),
        },
        "harness": {
            "instructions": harness,
            "appendixHash": sha256_bytes(harness_appendix.encode("utf-8")),
        },
        "commonHash": sha256_bytes(baseline_common.encode("utf-8")),
    }


ROUND_GATE = r'''#!/usr/bin/env python3
import json
import hashlib
import os
import socket
import sys
from pathlib import Path
import uuid

if len(sys.argv) != 2 or not sys.argv[1].isascii() or not sys.argv[1].isdigit():
    print("usage: benchmark-feedback <round-number>", file=sys.stderr)
    raise SystemExit(2)
request = {
    "schemaVersion": "agentic-palisade/round-request-v1",
    "round": int(sys.argv[1]),
    "requestId": uuid.uuid4().hex,
    "gateDigest": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
}
payload = (json.dumps(request, sort_keys=True) + "\n").encode("utf-8")
channel = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
try:
    channel.connect(os.environ["BENCHMARK_ROUND_SOCKET"])
    channel.sendall(payload)
    channel.shutdown(socket.SHUT_WR)
    response_bytes = bytearray()
    while b"\n" not in response_bytes:
        chunk = channel.recv(4096)
        if not chunk:
            break
        response_bytes.extend(chunk)
        if len(response_bytes) > 65536:
            raise RuntimeError("round supervisor response exceeded 65536 bytes")
finally:
    channel.close()
try:
    lines = bytes(response_bytes).splitlines()
    if len(lines) != 1:
        raise ValueError("round supervisor returned no single response")
    response = json.loads(lines[0])
    if response.get("requestId") != request["requestId"]:
        raise ValueError("round supervisor request identity mismatch")
    if response.get("round") != request["round"]:
        raise ValueError("round supervisor round mismatch")
    if response.get("gateDigest") != request["gateDigest"]:
        raise ValueError("round supervisor gate digest mismatch")
except (json.JSONDecodeError, ValueError) as error:
    print(f"benchmark-feedback: {error}", file=sys.stderr)
    raise SystemExit(4)
if response.get("accepted") is not True:
    print(json.dumps(response, sort_keys=True), file=sys.stderr)
    raise SystemExit(3)
print(json.dumps(response, sort_keys=True))
'''


class RoundSupervisor:
    def __init__(self, socket_path, workspace, gate_path):
        self.socket_path = Path(socket_path)
        self.workspace = Path(workspace)
        self.gate_path = Path(gate_path).resolve()
        self.gate_digest = sha256_bytes(self.gate_path.read_bytes())
        self.interpreter = Path(sys.executable).resolve()
        self.attempts = []
        self._errors = []
        self._stop = threading.Event()
        self._server = None
        self._thread = None
        self._connections = set()
        self._connections_lock = threading.Lock()

    def start(self):
        self.socket_path.parent.mkdir(parents=True, exist_ok=True)
        self._server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self._server.bind(str(self.socket_path))
        self.socket_path.chmod(0o600)
        self._server.listen(FIXED_ROUNDS + 2)
        self._server.settimeout(0.05)
        self._thread = threading.Thread(
            target=self._serve,
            name=f"round-supervisor-{self.workspace.parent.name}",
            daemon=True,
        )
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._server is not None:
            self._server.close()
        with self._connections_lock:
            connections = tuple(self._connections)
        if connections:
            self._errors.append(
                f"{len(connections)} round connection(s) active at shutdown")
        for connection in connections:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        if self._thread is not None:
            self._thread.join(timeout=2)
            if self._thread.is_alive():
                self._errors.append("round supervisor did not stop")
        try:
            self.socket_path.unlink()
        except FileNotFoundError:
            pass
        if self._errors:
            raise RuntimeError("; ".join(self._errors))

    def _serve(self):
        while not self._stop.is_set():
            try:
                connection, _ = self._server.accept()
            except socket.timeout:
                continue
            except OSError as error:
                if not self._stop.is_set():
                    self._errors.append(f"round accept failed: {error}")
                return
            with self._connections_lock:
                self._connections.add(connection)
            try:
                connection.settimeout(0.25)
                credentials = connection.getsockopt(
                    socket.SOL_SOCKET,
                    socket.SO_PEERCRED,
                    struct.calcsize("3i"),
                )
                peer_pid, _, _ = struct.unpack("3i", credentials)
                response = self._handle(connection, peer_pid)
                connection.sendall(
                    (json.dumps(response, sort_keys=True) + "\n").encode("utf-8"))
            except (OSError, struct.error) as error:
                if not self._stop.is_set():
                    self._errors.append(f"round connection failed: {error}")
            finally:
                with self._connections_lock:
                    self._connections.discard(connection)
                connection.close()

    def _handle(self, connection, peer_pid):
        payload = bytearray()
        failure = None
        try:
            while b"\n" not in payload:
                chunk = connection.recv(4096)
                if not chunk:
                    break
                payload.extend(chunk)
                if len(payload) > 65536:
                    break
        except socket.timeout:
            failure = "round request read timed out"
            self._errors.append(failure)
        except OSError as error:
            failure = f"round request read failed: {error}"
            if not self._stop.is_set():
                self._errors.append(failure)
        request = {}
        if failure is None:
            try:
                lines = bytes(payload).splitlines()
                if len(lines) != 1:
                    raise ValueError("request must contain exactly one JSON line")
                request = json.loads(lines[0])
                if not isinstance(request, dict):
                    raise ValueError("request must be an object")
                if set(request) != {
                        "schemaVersion", "round", "requestId", "gateDigest"}:
                    raise ValueError("request fields do not match the fixed gate schema")
                if request["schemaVersion"] != "agentic-palisade/round-request-v1":
                    raise ValueError("unsupported round request schema")
                if (isinstance(request["round"], bool)
                        or not isinstance(request["round"], int)):
                    raise ValueError("round must be an integer")
                if (not isinstance(request["requestId"], str)
                        or not re.fullmatch(r"[0-9a-f]{32}", request["requestId"])):
                    raise ValueError("requestId must be a UUID4 hex value")
                if (not isinstance(request["gateDigest"], str)
                        or not re.fullmatch(r"[0-9a-f]{64}", request["gateDigest"])):
                    raise ValueError("gateDigest must be a SHA-256 value")
            except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
                failure = str(error)

        accepted = [attempt for attempt in self.attempts if attempt["accepted"]]
        number = request.get("round", 0)
        request_id = request.get("requestId")
        if not isinstance(request_id, str):
            request_id = "invalid-" + uuid.uuid4().hex
        gate_digest = request.get("gateDigest")
        if not isinstance(gate_digest, str):
            gate_digest = "0" * 64
        if failure is None and gate_digest != self.gate_digest:
            failure = "request gate digest does not match fixed benchmark-feedback"
        if failure is None and not self._is_fixed_gate(peer_pid, number):
            failure = "request did not originate from fixed benchmark-feedback"
        expected = len(accepted) + 1
        if failure is None and any(not attempt["accepted"] for attempt in self.attempts):
            failure = "a prior round request was rejected"
        elif failure is None and number != expected:
            failure = f"expected round {expected}"
        elif failure is None and number > FIXED_ROUNDS:
            failure = "round limit exceeded"
        candidate_hash = "0" * 64
        if failure is None:
            try:
                candidate_hash = hash_candidate(self.workspace)
            except (OSError, ValueError) as error:
                failure = f"candidate hash failed: {error}"
        record = {
            "schemaVersion": "agentic-palisade/round-v1",
            "round": number,
            "accepted": failure is None,
            "timestamp": utc_now(),
            "candidateHash": candidate_hash,
            "requestId": request_id,
            "gateDigest": gate_digest,
            "channel": "runner-supervisor",
        }
        if failure is not None:
            record["failure"] = failure
        self.attempts.append(record)
        response = dict(record)
        if failure is None:
            response.update({
                "feedbackSources": [
                    "PROTOCOL.md",
                    "corpus/spec.json",
                    "corpus/reference/",
                ],
                "instruction": (
                    "Inspect only public corpus-declared evidence and this "
                    "workspace's bounded outputs."
                ),
            })
        return response

    def _is_fixed_gate(self, peer_pid, number):
        try:
            arguments = [
                argument.decode("utf-8")
                for argument in Path(f"/proc/{peer_pid}/cmdline").read_bytes().split(b"\0")
                if argument
            ]
            executable = Path(f"/proc/{peer_pid}/exe").resolve(strict=True)
            current_gate_digest = sha256_bytes(self.gate_path.read_bytes())
        except (OSError, UnicodeDecodeError):
            return False
        return (
            executable == self.interpreter
            and len(arguments) == 3
            and arguments[1] == str(self.gate_path)
            and arguments[2] == str(number)
            and current_gate_digest == self.gate_digest
        )


def _atomic_replace_bytes(path, content, mode=0o444):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.parent.chmod(0o700)
    temporary = path.parent / f".{path.name}.{uuid.uuid4().hex}.tmp"
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.chmod(mode)
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _publish_hashed_bytes(path, content, hash_path):
    digest = sha256_bytes(content)
    _atomic_replace_bytes(path, content)
    _atomic_replace_bytes(hash_path, (digest + "\n").encode("ascii"))
    return digest


def _authoritative_json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _trace_batch_id(hashes):
    shared = {
        name: hashes[name]
        for name in ("prompt", "corpus", "template", "protocol")
    }
    return sha256_bytes(_authoritative_json_bytes(shared))


def _empty_telemetry(batch_id="unavailable", run_id="unavailable"):
    return {
        "tokens": {name: {"status": "unavailable", "value": None}
                   for name in ("input", "output", "cacheRead", "cacheWrite", "reasoning")},
        "toolCalls": {},
        "edits": 0,
        "builds": 0,
        "launches": 0,
        "screenshots": 0,
        "captureEvents": {
            "attempted": 0,
            "schemaRejected": 0,
            "accepted": 0,
            "inspected": 0,
            "compared": 0,
            "stale": 0,
            "completionUsed": 0,
            "launcherCaptures": 0,
        },
        "failedOperations": [],
        "traceTaxonomy": {
            "schemaVersion": "agentic-palisade/trace-taxonomy-v1",
            "availability": "unavailable",
            "parserVersion": "unavailable",
            "batchId": batch_id,
            "runId": run_id,
            "sessionId": None,
            "inputSha256": None,
            "sequenceBasis": "unavailable",
            "knownExclusions": [
                "session export unavailable",
                "hidden reasoning",
                "causal inference from association",
            ],
            "evidenceGaps": [],
            "events": [],
            "captureAttempts": [],
            "captureLifecycle": {},
            "attributions": {
                "capture": [],
                "semantic": [],
                "rendering": [],
                "workflow-loop": [],
            },
            "joins": {
                "process": {"status": "unavailable", "identity": None},
                "rounds": {"status": "unavailable", "identities": []},
                "evaluation": {"status": "unavailable", "identity": None},
            },
        },
    }


def _relative(output, path):
    return Path(path).relative_to(output).as_posix()


def _prepare_run(
        output, pair, treatment, index, hashes, treatment_inputs,
        model, reasoning, profile, max_seconds,
        candidate_repository=None, candidate_version=None):
    run_id = str(uuid.uuid4())
    run_dir = output / "runs" / run_id
    repository = run_dir / "repository"
    benchmark = repository / "benchmarks/agentic-palisade"
    workspace = benchmark / "template"
    profile_root = run_dir / "profile-home"
    cache_root = run_dir / "cache"
    session_root = run_dir / "sessions"
    artifact_root = run_dir / "artifacts"
    log_root = run_dir / "logs"
    binary_root = run_dir / "bin"
    temporary_root = run_dir / "tmp"
    gradle_cache = cache_root / "gradle"

    for path in (profile_root, cache_root, session_root, artifact_root, log_root,
                 binary_root, temporary_root, repository / "gradle"):
        path.mkdir(parents=True, exist_ok=False)
    shutil.copy2(REPOSITORY_ROOT / "gradlew", repository / "gradlew")
    shutil.copy2(REPOSITORY_ROOT / "gradlew.bat", repository / "gradlew.bat")
    _copy_tree(REPOSITORY_ROOT / "gradle/wrapper", repository / "gradle/wrapper")
    _copy_tree(BENCHMARK_ROOT / "template", workspace)
    shutil.copy2(BENCHMARK_ROOT / "PROTOCOL.md", workspace / "PROTOCOL.md")
    _copy_tree(BENCHMARK_ROOT / "corpus", workspace / "corpus")
    (workspace / "INSTRUCTIONS.md").write_text(
        treatment_inputs[treatment]["instructions"], encoding="utf-8")
    instructions_hash = sha256_bytes((workspace / "INSTRUCTIONS.md").read_bytes())
    _make_read_only(workspace / "INSTRUCTIONS.md")
    _make_read_only(workspace / "PROTOCOL.md")
    _make_read_only(workspace / "corpus")
    treatment_overlay_hash = None
    if treatment == "harness":
        _copy_tree(BENCHMARK_ROOT / "treatments/harness", benchmark / "treatments/harness")
        if candidate_repository is not None:
            _copy_tree(
                candidate_repository,
                benchmark / "treatments/harness/candidate-maven")
            (benchmark / "treatments/harness/candidate-version.txt").write_text(
                candidate_version + "\n", encoding="ascii")
        treatment_overlay_hash = hash_tree(
            benchmark / "treatments/harness", ignored_names=frozenset())
        _make_read_only(benchmark / "treatments/harness")

    prompt_path = run_dir / "task.md"
    shutil.copy2(BENCHMARK_ROOT / "prompts/task.md", prompt_path)
    gate_path = binary_root / "benchmark-feedback"
    gate_path.write_text(ROUND_GATE, encoding="utf-8")
    gate_path.chmod(0o555)
    prompt_path.chmod(0o444)
    round_log = artifact_root / "rounds.jsonl"
    round_hash = artifact_root / "rounds.sha256"
    round_socket = (
        Path(tempfile.gettempdir())
        / f"palisade-round-{run_id}.sock")
    initial_candidate_hash = hash_candidate(workspace)

    item = {
        "runId": run_id,
        "pair": pair,
        "treatment": treatment,
        "display": f":{220 + index}",
        "workspace": _relative(output, workspace),
        "profileRoot": _relative(output, profile_root),
        "cacheRoot": _relative(output, cache_root),
        "sessionRoot": _relative(output, session_root),
        "artifactRoot": _relative(output, artifact_root),
        "inputManifest": _relative(output, run_dir / "input-manifest.json"),
        "runRecord": _relative(output, run_dir / "run-record.json"),
        "runRecordHash": _relative(output, run_dir / "run-record.sha256"),
        "roundEvidence": _relative(output, round_log),
        "roundEvidenceHash": _relative(output, round_hash),
        "initialCandidateHash": initial_candidate_hash,
        "treatmentAppendixHash": treatment_inputs[treatment]["appendixHash"],
        "instructionsHash": instructions_hash,
        "treatmentOverlayHash": treatment_overlay_hash,
    }
    input_manifest = {
        "schemaVersion": "agentic-palisade/input-manifest-v1",
        "createdAt": utc_now(),
        "runId": run_id,
        "pair": pair,
        "treatment": treatment,
        "model": model,
        "reasoning": reasoning,
        "profile": profile,
        "maxTimeSeconds": max_seconds,
        "rounds": FIXED_ROUNDS,
        "protocolAmendment": PROTOCOL_AMENDMENT,
        "hashes": {
            **hashes,
            "initialCandidate": initial_candidate_hash,
            "treatmentAppendix": treatment_inputs[treatment]["appendixHash"],
            "instructions": instructions_hash,
            "treatmentOverlay": treatment_overlay_hash,
        },
        "paths": {key: item[key] for key in (
            "workspace", "profileRoot", "cacheRoot", "sessionRoot", "artifactRoot")},
    }
    input_manifest_path = output / item["inputManifest"]
    _write_exclusive_json(input_manifest_path, input_manifest)
    item["inputManifestHash"] = sha256_bytes(input_manifest_path.read_bytes())
    item["_runtime"] = {
        "runDir": run_dir,
        "workspace": workspace,
        "profileRoot": profile_root,
        "cacheRoot": cache_root,
        "sessionRoot": session_root,
        "artifactRoot": artifact_root,
        "logRoot": log_root,
        "binaryRoot": binary_root,
        "temporaryRoot": temporary_root,
        "gradleCache": gradle_cache,
        "prompt": prompt_path,
        "instructions": workspace / "INSTRUCTIONS.md",
        "roundLog": round_log,
        "roundHash": round_hash,
        "roundSocket": round_socket,
        "gate": gate_path,
    }
    return item


def _public_item(item):
    return {key: value for key, value in item.items() if key != "_runtime"}


def _candidate_repository(path, version):
    if not CANDIDATE_VERSION.fullmatch(version or ""):
        raise ValueError("candidate version must be a non-SNAPSHOT semantic version")
    repository = Path(path).resolve()
    if not repository.is_dir() or repository.is_symlink():
        raise ValueError("candidate Maven repository must be a local directory")
    for module in CANDIDATE_MODULES:
        module_root = repository / "io/github/teemuki8" / module / version
        for suffix in (".jar", ".pom"):
            artifact = module_root / f"{module}-{version}{suffix}"
            if (not artifact.is_file() or artifact.is_symlink()
                    or artifact.stat().st_size == 0):
                raise ValueError(f"candidate Maven artifact is missing: {artifact}")
    return repository, hash_tree(repository, ignored_names=frozenset())


def _prepared_path(output, relative):
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise ValueError("prepared run path must be a non-empty relative path")
    path = (output / relative).resolve()
    try:
        path.relative_to(output)
    except ValueError as error:
        raise ValueError("prepared run path escapes the output root") from error
    return path


def _restore_run(output, item, hashes):
    if not isinstance(item, dict):
        raise ValueError("prepared run entry must be an object")
    required = {
        "runId", "pair", "treatment", "display", "workspace", "profileRoot",
        "cacheRoot", "sessionRoot", "artifactRoot", "inputManifest",
        "runRecord", "runRecordHash", "roundEvidence", "roundEvidenceHash",
        "initialCandidateHash", "treatmentAppendixHash", "instructionsHash",
        "treatmentOverlayHash", "inputManifestHash",
    }
    if set(item) != required:
        raise ValueError("prepared run fields do not match the fixed schema")
    try:
        uuid.UUID(item["runId"])
    except (TypeError, ValueError, AttributeError) as error:
        raise ValueError("prepared run ID is malformed") from error
    run_dir = _prepared_path(output, item["inputManifest"]).parent
    workspace = _prepared_path(output, item["workspace"])
    input_path = _prepared_path(output, item["inputManifest"])
    if sha256_bytes(input_path.read_bytes()) != item["inputManifestHash"]:
        raise ValueError(f"prepared input manifest changed: {item['runId']}")
    input_manifest = json.loads(input_path.read_text(encoding="utf-8"))
    if (input_manifest.get("runId") != item["runId"]
            or input_manifest.get("pair") != item["pair"]
            or input_manifest.get("treatment") != item["treatment"]
            or any(input_manifest.get("hashes", {}).get(key) != value
                   for key, value in hashes.items())):
        raise ValueError(f"prepared input identity changed: {item['runId']}")
    drift = verify_protected_inputs(workspace, input_manifest["hashes"])
    if drift:
        raise ValueError(
            f"prepared protected inputs changed: {item['runId']}: {drift}")
    observed_candidate = hash_candidate(workspace)
    if (observed_candidate != item["initialCandidateHash"]
            or observed_candidate !=
            input_manifest["hashes"].get("initialCandidate")):
        raise ValueError(f"prepared candidate changed: {item['runId']}")
    for field in ("runRecord", "runRecordHash", "roundEvidence", "roundEvidenceHash"):
        if _prepared_path(output, item[field]).exists():
            raise ValueError(f"prepared output already exists: {item[field]}")
    item = dict(item)
    item["_runtime"] = {
        "runDir": run_dir,
        "workspace": workspace,
        "profileRoot": _prepared_path(output, item["profileRoot"]),
        "cacheRoot": _prepared_path(output, item["cacheRoot"]),
        "sessionRoot": _prepared_path(output, item["sessionRoot"]),
        "artifactRoot": _prepared_path(output, item["artifactRoot"]),
        "logRoot": run_dir / "logs",
        "binaryRoot": run_dir / "bin",
        "temporaryRoot": run_dir / "tmp",
        "gradleCache": _prepared_path(output, item["cacheRoot"]) / "gradle",
        "prompt": run_dir / "task.md",
        "instructions": workspace / "INSTRUCTIONS.md",
        "roundLog": _prepared_path(output, item["roundEvidence"]),
        "roundHash": _prepared_path(output, item["roundEvidenceHash"]),
        "roundSocket": Path(tempfile.gettempdir()) / f"palisade-round-{item['runId']}.sock",
        "gate": run_dir / "bin/benchmark-feedback",
    }
    return item


def _sanitized_environment(item):
    runtime = item["_runtime"]
    environment = {key: value for key, value in os.environ.items()
                   if key in SAFE_PARENT_ENV and key not in CREDENTIAL_KEYS and not key.endswith("_API_KEY")}
    environment.update({
        "HOME": str(runtime["profileRoot"]),
        "XDG_CONFIG_HOME": str(runtime["profileRoot"] / ".config"),
        "XDG_CACHE_HOME": str(runtime["cacheRoot"]),
        "XDG_STATE_HOME": str(runtime["profileRoot"] / ".state"),
        "XDG_DATA_HOME": str(runtime["profileRoot"] / ".local/share"),
        "XDG_RUNTIME_DIR": str(runtime["temporaryRoot"] / "xdg-runtime"),
        "TMPDIR": str(runtime["temporaryRoot"]),
        "GRADLE_USER_HOME": str(runtime["gradleCache"]),
        "PI_ARTIFACTS_DIR": str(runtime["artifactRoot"] / "omp-artifacts"),
        "PATH": str(runtime["binaryRoot"]) + os.pathsep + os.environ.get("PATH", "/usr/bin:/bin"),
        "DISPLAY": item["display"],
        "BENCHMARK_RUN_ID": item["runId"],
        "BENCHMARK_PAIR": str(item["pair"]),
        "BENCHMARK_TREATMENT": item["treatment"],
        "BENCHMARK_WORKSPACE": str(runtime["workspace"]),
        "BENCHMARK_SESSION_ROOT": str(runtime["sessionRoot"]),
        "BENCHMARK_ARTIFACT_ROOT": str(runtime["artifactRoot"]),
        "BENCHMARK_ROUND_SOCKET": str(runtime["roundSocket"]),
        "BENCHMARK_ROUND_GATE": str(runtime["gate"]),
    })
    runtime_directory = Path(environment["XDG_RUNTIME_DIR"])
    runtime_directory.mkdir(mode=0o700)
    for path in (Path(environment["XDG_CONFIG_HOME"]), Path(environment["XDG_STATE_HOME"]),
                 Path(environment["XDG_DATA_HOME"]), Path(environment["GRADLE_USER_HOME"]),
                 Path(environment["PI_ARTIFACTS_DIR"])):
        path.mkdir(parents=True, exist_ok=True)
    return environment


def _process_group_exists(process_group):
    try:
        os.killpg(process_group, 0)
        return True
    except ProcessLookupError:
        return False


def _process_group_has_live_members(process_group):
    for process_path in Path("/proc").iterdir():
        if not process_path.name.isdigit():
            continue
        try:
            fields = (process_path / "stat").read_text().rsplit(")", 1)[1].split()
            state = fields[0]
            member_group = int(fields[2])
        except (FileNotFoundError, PermissionError, IndexError, ValueError):
            continue
        if member_group == process_group and state != "Z":
            return True
    return False


def _wait_for_process_group_exit(process_group, timeout_seconds):
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if (not _process_group_exists(process_group)
                or not _process_group_has_live_members(process_group)):
            return True
        time.sleep(0.01)
    return (
        not _process_group_exists(process_group)
        or not _process_group_has_live_members(process_group)
    )


def quiesce_process_group(process_group, grace_seconds=0.05):
    if (not _process_group_exists(process_group)
            or not _process_group_has_live_members(process_group)):
        return None
    message = "descendant process group remained active after OMP exit"
    try:
        os.killpg(process_group, signal.SIGTERM)
    except ProcessLookupError:
        return message
    time.sleep(grace_seconds)
    if _process_group_exists(process_group):
        try:
            os.killpg(process_group, signal.SIGKILL)
        except ProcessLookupError:
            pass
    if not _wait_for_process_group_exit(process_group, 0.25):
        return "descendant process group did not quiesce after SIGKILL"
    return message



def _omp_command(omp, item, model, reasoning, max_time_text):
    runtime = item["_runtime"]
    return [
        omp,
        "--model", model,
        "--thinking", reasoning,
        "--profile", item["runId"],
        "--session-dir", str(runtime["sessionRoot"]),
        "--cwd", str(runtime["workspace"]),
        "--mode", "json",
        "--print",
        "--max-time", max_time_text,
        "--tools", TOOL_ALLOWLIST,
        "--auto-approve",
        "--approval-mode", "yolo",
        "--no-extensions",
        "--no-skills",
        "--no-rules",
        "--no-lsp",
        "--no-title",
        "@" + str(runtime["prompt"]),
        "@" + str(runtime["instructions"]),
    ]


def terminate_process_group(process, grace_seconds=0.25):
    process_group = process.pid
    deadline = time.monotonic() + grace_seconds
    try:
        os.killpg(process_group, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=grace_seconds)
    except subprocess.TimeoutExpired:
        pass
    remaining = deadline - time.monotonic()
    if remaining > 0:
        time.sleep(remaining)
    if _process_group_exists(process_group):
        try:
            os.killpg(process_group, signal.SIGKILL)
        except ProcessLookupError:
            pass
    try:
        process.wait(timeout=0.25)
    except subprocess.TimeoutExpired:
        return "OMP leader did not exit after SIGKILL"
    if not _wait_for_process_group_exit(process_group, 0.25):
        return "timed-out process group did not quiesce after SIGKILL"
    return None


def wait_for_process_group(process, timeout_seconds):
    try:
        return_code = process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        shutdown_error = terminate_process_group(process)
        return process.returncode, True, shutdown_error
    quiescence_error = quiesce_process_group(process.pid)
    return return_code, False, quiescence_error


def classify_process_exit(return_code, timed_out):
    if timed_out:
        return "timed_out"
    if return_code is None:
        return "crashed"
    if return_code < 0:
        return "crashed"
    if return_code != 0:
        return "nonzero_exit"
    return "success"


def _unrecoverable(classifications):
    return any(classification != "success" for classification in classifications)


def _failure_reason(classifications, runs):
    for item, classification in zip(runs, classifications):
        if classification != "success":
            return {"runId": item["runId"], "classification": classification}
    return None


class ManagedDisplay:
    READY_SECONDS = 10

    def __init__(self, display, log_root, executable="Xvfb"):
        match = LOCAL_DISPLAY.fullmatch(display)
        if match is None:
            raise ValueError("managed display must be a local nonzero X11 display")
        self.display = display
        self.socket = Path("/tmp/.X11-unix") / f"X{match.group('number')}"
        self.log_root = Path(log_root)
        self.executable = executable
        self.process = None
        self.stdout = None
        self.stderr = None

    def start(self):
        if self.socket.exists():
            raise RuntimeError(f"display socket already exists: {self.socket}")
        self.stdout = (self.log_root / "xvfb.stdout.log").open("xb")
        self.stderr = (self.log_root / "xvfb.stderr.log").open("xb")
        try:
            self.process = subprocess.Popen(
                [
                    self.executable, self.display,
                    "-screen", "0", "1920x1080x24",
                    "-nolisten", "tcp", "-noreset",
                ],
                stdin=subprocess.DEVNULL,
                stdout=self.stdout,
                stderr=self.stderr,
                start_new_session=True,
            )
            deadline = time.monotonic() + self.READY_SECONDS
            waiter = threading.Event()
            while time.monotonic() < deadline:
                if self.socket.exists() and self.socket.is_socket():
                    return
                return_code = self.process.poll()
                if return_code is not None:
                    raise RuntimeError(
                        f"Xvfb exited before readiness with status {return_code}")
                waiter.wait(min(0.02, deadline - time.monotonic()))
            raise RuntimeError(f"Xvfb did not create {self.socket}")
        except BaseException:
            self.stop()
            raise

    def stop(self):
        failure = None
        if self.process is not None:
            return_code = self.process.poll()
            if return_code is not None and return_code != 0:
                failure = f"Xvfb exited unexpectedly with status {return_code}"
            else:
                failure = terminate_process_group(self.process)
            self.process = None
        for stream in (self.stdout, self.stderr):
            if stream is not None:
                stream.close()
        self.stdout = None
        self.stderr = None
        if failure is not None:
            raise RuntimeError(failure.replace("OMP", "Xvfb"))


class BrokerRelay:
    MAX_BODY_BYTES = 8 * 1024 * 1024

    def __init__(self, upstream_url, bearer_token, config_path):
        upstream = urllib.parse.urlsplit(upstream_url)
        if (upstream_url != FIXED_BROKER_URL
                or upstream.scheme != "http"
                or upstream.hostname != "127.0.0.1"
                or upstream.port is None
                or upstream.path not in ("", "/")):
            raise ValueError("authentication preflight failed: invalid broker endpoint")
        self._upstream = upstream
        self._bearer_token = bearer_token
        self._config_path = Path(config_path)
        self._config_directory = self._config_path.parent
        self._client_token = secrets.token_urlsafe(32)
        self._lock = threading.Lock()
        self._claimed = False
        self._retired = False
        self.served = False
        self._handler_condition = threading.Condition()
        self._active_handlers = 0
        relay = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def handle(self):
                relay._handler_started()
                try:
                    super().handle()
                finally:
                    relay._handler_stopped()

            def do_GET(self):
                relay._forward(self)

            def do_POST(self):
                relay._forward(self)

            def do_PUT(self):
                relay._forward(self)

            def log_message(self, *_):
                pass

        self._server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._server.daemon_threads = True
        self._thread = threading.Thread(
            target=self._server.serve_forever,
            name="palisade-auth-relay",
            daemon=True,
        )

    @property
    def config(self):
        return {
            "url": f"http://127.0.0.1:{self._server.server_port}",
            "token": self._client_token,
        }

    def start(self):
        self._thread.start()

    def _handler_started(self):
        with self._handler_condition:
            self._active_handlers += 1

    def _handler_stopped(self):
        with self._handler_condition:
            self._active_handlers -= 1
            self._handler_condition.notify_all()

    def _forward(self, request):
        expected = "Bearer " + self._client_token
        with self._lock:
            accepted = (
                not self._claimed
                and secrets.compare_digest(
                    request.headers.get("Authorization", ""), expected)
            )
            if accepted:
                self._claimed = True
        if not accepted:
            request.send_error(403)
            return

        length = int(request.headers.get("Content-Length", "0"))
        if length < 0 or length > self.MAX_BODY_BYTES:
            request.send_error(413)
            self.retire()
            return
        body = request.rfile.read(length)
        headers = {
            key: value for key, value in request.headers.items()
            if key.lower() not in {
                "authorization", "connection", "content-length", "host",
                "proxy-authorization", "transfer-encoding",
            }
        }
        headers.update({
            "Authorization": "Bearer " + self._bearer_token,
            "Connection": "close",
            "Content-Length": str(len(body)),
            "Host": self._upstream.netloc,
        })
        connection = http.client.HTTPConnection(
            self._upstream.hostname, self._upstream.port, timeout=10)
        try:
            connection.request(request.command, request.path, body=body, headers=headers)
            response = connection.getresponse()
            content = response.read(self.MAX_BODY_BYTES + 1)
            if len(content) > self.MAX_BODY_BYTES:
                raise OSError("broker response exceeded relay bound")
            self.served = True
            self.retire()
            request.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in {
                    "connection", "content-length", "transfer-encoding",
                }:
                    request.send_header(key, value)
            request.send_header("Content-Length", str(len(content)))
            request.send_header("Connection", "close")
            request.end_headers()
            request.wfile.write(content)
        except OSError:
            request.send_error(502)
        finally:
            connection.close()
            if not self.served:
                self.retire()

    def retire(self):
        with self._lock:
            if self._retired:
                return
            self._retired = True
            try:
                self._config_path.write_bytes(b"")
            except FileNotFoundError:
                pass
            try:
                self._config_path.unlink(missing_ok=True)
                self._config_directory.rmdir()
            except OSError:
                pass
        self._server.shutdown()

    def stop(self):
        self.retire()
        self._thread.join(timeout=2)
        deadline = time.monotonic() + 12
        with self._handler_condition:
            while self._active_handlers and time.monotonic() < deadline:
                self._handler_condition.wait(deadline - time.monotonic())
            active_handlers = self._active_handlers
        self._server.server_close()
        if self._thread.is_alive() or active_handlers:
            raise RuntimeError("authentication relay did not stop")


def _broker_config_lifecycle(config_path, broker_url, bearer_token):
    config_path = Path(config_path)
    config_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        relay = BrokerRelay(broker_url, bearer_token, config_path)
        content = (
            json.dumps({"auth": {"broker": relay.config}}, separators=(",", ":"))
            + "\n"
        ).encode("utf-8")
        with config_path.open("xb") as stream:
            stream.write(content)
        config_path.chmod(0o600)
        relay.start()
    except BaseException:
        config_path.unlink(missing_ok=True)
        raise
    return relay


def _preflight_environment(root):
    root = Path(root)
    environment = {
        key: value for key, value in os.environ.items()
        if key in SAFE_PARENT_ENV
        and key not in CREDENTIAL_KEYS
        and not key.endswith("_API_KEY")
    }
    environment.update({
        "HOME": str(root),
        "XDG_CONFIG_HOME": str(root / ".config"),
        "XDG_CACHE_HOME": str(root / ".cache"),
        "XDG_STATE_HOME": str(root / ".state"),
        "XDG_DATA_HOME": str(root / ".local/share"),
        "TMPDIR": str(root / "tmp"),
    })
    for key in ("XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME",
                "XDG_DATA_HOME", "TMPDIR"):
        Path(environment[key]).mkdir(parents=True, exist_ok=False)
    return environment


def _broker_owner_environment():
    return {
        key: value for key, value in os.environ.items()
        if key in BROKER_OWNER_ENV
    }


def _load_broker_token(omp):
    try:
        completed = subprocess.run(
            [omp, "auth-broker", "token"],
            env=_broker_owner_environment(),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError("authentication preflight failed: broker bearer unavailable") from error
    if completed.returncode != 0:
        raise ValueError("authentication preflight failed: broker bearer unavailable")
    try:
        token = completed.stdout.decode("utf-8").strip()
    except UnicodeDecodeError as error:
        raise ValueError("authentication preflight failed: broker bearer unavailable") from error
    if not token or len(token) > 8192 or "\n" in token or "\x00" in token:
        raise ValueError("authentication preflight failed: broker bearer unavailable")
    return token


def _run_auth_preflight(omp, model, reasoning, broker_url, bearer_token):
    with tempfile.TemporaryDirectory(prefix="palisade-auth-preflight-") as temporary:
        root = Path(temporary)
        environment = _preflight_environment(root)
        config_path = (
            root / ".omp/profiles/palisade-auth-preflight/agent/config.yml")
        relay = _broker_config_lifecycle(
            config_path, broker_url, bearer_token)
        command = [
            omp,
            "--model", model,
            "--thinking", reasoning,
            "--profile", "palisade-auth-preflight",
            "--cwd", str(root),
            "--allow-home",
            "--mode", "json",
            "--print",
            "--max-time", "3m",
            "--no-session",
            "--no-tools",
            "--no-extensions",
            "--no-skills",
            "--no-rules",
            "--no-lsp",
            "--no-title",
            "Reply with exactly AUTHENTICATED.",
        ]
        try:
            process = subprocess.Popen(
                command,
                cwd=root,
                env=environment,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                start_new_session=True,
            )
        except OSError as error:
            relay.stop()
            raise ValueError("authentication preflight failed: OMP did not start") from error
        try:
            try:
                stdout, _ = process.communicate(timeout=AUTH_PREFLIGHT_SECONDS)
                quiescence_error = quiesce_process_group(process.pid)
            except subprocess.TimeoutExpired as error:
                terminate_process_group(process)
                process.communicate()
                raise ValueError(
                    "authentication preflight failed: exact-model request timed out"
                ) from error
        finally:
            relay.stop()
        if quiescence_error:
            raise ValueError(
                "authentication preflight failed: process group did not quiesce")
        if not relay.served:
            raise ValueError(
                "authentication preflight failed: broker relay was not accepted")
        if process.returncode != 0 or not stdout.strip():
            raise ValueError("authentication preflight failed: exact-model request was rejected")


def _discover_session(session_root):
    candidates = sorted(
        path for path in Path(session_root).rglob("*.jsonl") if path.is_file())
    if len(candidates) != 1:
        raise TELEMETRY.TelemetryError(
            f"expected exactly one OMP session export, found {len(candidates)}")
    return candidates[0]


def _parse_telemetry(session_path, workspace, identity):
    try:
        return TELEMETRY.parse_omp_session(
            session_path, workspace, identity), None
    except (TELEMETRY.TelemetryError,
            TELEMETRY.TRACE.TraceTaxonomyError) as error:
        return _empty_telemetry(
            identity.get("batchId", "unavailable"),
            identity.get("runId", "unavailable")), str(error)


def _open_measured_process(command, item, runtime, environment, stdout, stderr,
                           broker_url, bearer_token):
    config_path = (
        runtime["profileRoot"] / ".omp/profiles"
        / item["runId"] / "agent/config.yml"
    )
    relay = _broker_config_lifecycle(
        config_path, broker_url, bearer_token)
    try:
        process = subprocess.Popen(
            command,
            cwd=runtime["workspace"],
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=stdout,
            stderr=stderr,
            start_new_session=True,
        )
    except BaseException:
        relay.stop()
        raise
    return process, relay


def _run_one(output, item, omp, model, reasoning, max_time_text, max_time_seconds,
             hashes, broker_url, bearer_token, manage_display):
    runtime = item["_runtime"]
    started_at = utc_now()
    started = time.monotonic()
    command = _omp_command(omp, item, model, reasoning, max_time_text)
    environment = _sanitized_environment(item)
    stdout_path = runtime["logRoot"] / "omp.stdout.jsonl"
    stderr_path = runtime["logRoot"] / "omp.stderr.log"
    timed_out = False
    return_code = None
    launch_error = None
    supervisor_failures = []
    display_failures = []
    round_supervisor = None
    auth_relay = None
    display = None
    try:
        round_supervisor = RoundSupervisor(
            runtime["roundSocket"], runtime["workspace"], runtime["gate"])
        round_supervisor.start()
    except (OSError, RuntimeError) as error:
        supervisor_failures.append(f"round supervisor failed to start: {error}")
    with stdout_path.open("xb") as stdout, stderr_path.open("xb") as stderr:
        if not supervisor_failures:
            try:
                if manage_display:
                    display = ManagedDisplay(item["display"], runtime["logRoot"])
                    display.start()
                process, auth_relay = _open_measured_process(
                    command, item, runtime, environment, stdout, stderr,
                    broker_url, bearer_token)
                return_code, timed_out, shutdown_error = wait_for_process_group(
                    process, max_time_seconds)
                if shutdown_error:
                    supervisor_failures.append(shutdown_error)
            except (OSError, RuntimeError, ValueError) as error:
                launch_error = str(error)
            finally:
                if auth_relay is not None:
                    try:
                        auth_relay.stop()
                    except RuntimeError as error:
                        supervisor_failures.append(str(error))
                if display is not None:
                    try:
                        display.stop()
                    except RuntimeError as error:
                        display_failures.append(str(error))
    if round_supervisor is not None:
        try:
            round_supervisor.stop()
        except (OSError, RuntimeError) as error:
            supervisor_failures.append(str(error))
    round_content = b"".join(
        (json.dumps(attempt, sort_keys=True) + "\n").encode("utf-8")
        for attempt in (round_supervisor.attempts if round_supervisor else ())
    )
    _publish_hashed_bytes(runtime["roundLog"], round_content, runtime["roundHash"])
    finished_at = utc_now()
    wall_time = time.monotonic() - started
    failures = []
    if launch_error:
        failures.append({"phase": "omp_launch", "message": launch_error})
    for supervisor_failure in supervisor_failures:
        failures.append({"phase": "round_supervisor", "message": supervisor_failure})
    for display_failure in display_failures:
        failures.append({"phase": "display_server", "message": display_failure})
    if timed_out:
        failures.append({"phase": "deadline", "message": f"exceeded {max_time_text}"})
    elif return_code not in (0, None):
        failures.append({"phase": "omp_exit", "message": f"exit status {return_code}"})

    batch_id = _trace_batch_id(hashes)
    telemetry = _empty_telemetry(batch_id, item["runId"])
    telemetry_error = None
    session_path = None
    try:
        session_path = _discover_session(runtime["sessionRoot"])
        telemetry, telemetry_error = _parse_telemetry(
            session_path,
            runtime["workspace"],
            {"batchId": batch_id, "runId": item["runId"]},
        )
        if telemetry_error is not None:
            failures.append({"phase": "telemetry", "message": telemetry_error})
    except TELEMETRY.TelemetryError as error:
        telemetry_error = str(error)
        failures.append({"phase": "telemetry", "message": telemetry_error})

    rounds = []
    round_error = None
    try:
        rounds = TELEMETRY.parse_round_log(runtime["roundLog"], FIXED_ROUNDS)
    except TELEMETRY.RoundProtocolError as error:
        rounds = error.markers
        round_error = str(error)
        failures.append({"phase": "round_protocol", "message": round_error})

    protected_drift = verify_protected_inputs(
        runtime["workspace"],
        {
            **hashes,
            "instructions": item["instructionsHash"],
            "treatmentOverlay": item["treatmentOverlayHash"],
        },
    )
    for drift in protected_drift:
        failures.append({"phase": "protected_input_integrity", **drift})

    final_candidate_hash = None
    final_hash_error = None
    try:
        final_candidate_hash = hash_candidate(runtime["workspace"])
    except (OSError, ValueError) as error:
        final_hash_error = str(error)
        failures.append({"phase": "final_candidate_hash", "message": final_hash_error})

    for operation in telemetry["failedOperations"]:
        failures.append({"phase": "tool", **operation})

    classification = classify_process_exit(return_code, timed_out)
    if supervisor_failures:
        classification = "round_supervisor_failure"
    elif display_failures:
        classification = "crashed"
    elif launch_error:
        classification = "crashed"
    elif classification == "success" and protected_drift:
        classification = "input_integrity_failure"
    elif classification == "success" and telemetry_error:
        classification = "telemetry_failure"
    elif classification == "success" and round_error:
        classification = "round_protocol_failure"
    elif classification == "success" and final_hash_error:
        classification = "candidate_integrity_failure"

    process_identity = {
        "runId": item["runId"],
        "startedAt": started_at,
        "finishedAt": finished_at,
        "classification": classification,
        "code": return_code if return_code is not None and return_code >= 0 else None,
        "signal": -return_code if return_code is not None and return_code < 0 else None,
        "timedOut": timed_out,
    }
    trace_joins = telemetry["traceTaxonomy"]["joins"]
    trace_joins["process"] = {
        "status": "available",
        "identity": {
            **process_identity,
            "sha256": sha256_bytes(
                _authoritative_json_bytes(process_identity)),
        },
    }
    round_identities = [{
        "round": marker["round"],
        "requestId": marker.get("requestId"),
        "gateDigest": marker.get("gateDigest"),
        "candidateHash": marker["candidateHash"],
    } for marker in rounds]
    trace_joins["rounds"] = {
        "status": "available" if round_error is None else "partial",
        "identities": round_identities,
    }

    record = {
        "schemaVersion": "agentic-palisade/run-record-v1",
        "runId": item["runId"],
        "pair": item["pair"],
        "treatment": item["treatment"],
        "model": model,
        "reasoning": reasoning,
        "timestamps": {"startedAt": started_at, "finishedAt": finished_at},
        "wallTimeSeconds": round(wall_time, 6),
        "exit": {
            "classification": classification,
            "code": return_code if return_code is not None and return_code >= 0 else None,
            "signal": -return_code if return_code is not None and return_code < 0 else None,
            "timedOut": timed_out,
        },
        "hashes": {
            **hashes,
            "inputManifest": item["inputManifestHash"],
            "treatmentAppendix": item["treatmentAppendixHash"],
            "instructions": item["instructionsHash"],
            "treatmentOverlay": item["treatmentOverlayHash"],
            "initialCandidate": item["initialCandidateHash"],
            "finalCandidate": final_candidate_hash,
        },
        "paths": {
            "workspace": item["workspace"],
            "profileRoot": item["profileRoot"],
            "cacheRoot": item["cacheRoot"],
            "sessionRoot": item["sessionRoot"],
            "artifactRoot": item["artifactRoot"],
            "stdout": _relative(output, stdout_path),
            "stderr": _relative(output, stderr_path),
            "sessionExport": _relative(output, session_path) if session_path else None,
            "roundEvidence": item["roundEvidence"],
            "roundEvidenceHash": item["roundEvidenceHash"],
            "runRecordHash": item["runRecordHash"],
        },
        "telemetry": telemetry,
        "rounds": rounds,
        "failures": failures,
    }
    _publish_hashed_bytes(
        output / item["runRecord"],
        _authoritative_json_bytes(record),
        output / item["runRecordHash"],
    )
    return classification


def _validate_arguments(arguments, max_seconds):
    if arguments.profile not in ("low-confidence", "high-confidence"):
        raise ValueError(f"unknown benchmark profile: {arguments.profile}")
    if arguments.profile == "high-confidence":
        if MODEL_IMAGE_CAPABLE.get(arguments.model) is False:
            raise ValueError(
                f"model {arguments.model} does not support image input "
                f"required by profile {arguments.profile}")
        if MODEL_IMAGE_CAPABLE.get(arguments.model) is None:
            raise ValueError(
                f"model image capability unknown for {arguments.model}; "
                f"add it to MODEL_IMAGE_CAPABLE")
    profile_pairs = 3 if arguments.profile == "low-confidence" else 5
    required_pairs = profile_pairs if arguments.release_candidate else FIXED_PAIRS
    effective_pairs = arguments.pairs or profile_pairs
    if effective_pairs != required_pairs:
        raise ValueError(f"pairs must be exactly {required_pairs}")
    arguments.pairs = effective_pairs
    if max_seconds < MIN_SECONDS:
        raise ValueError(f"--max-time must be at least {MIN_SECONDS // 60} minutes")
    if not arguments.reasoning or len(arguments.reasoning) > 64:
        raise ValueError("--reasoning must be a non-empty string of at most 64 characters")
    if arguments.qualification and Path(arguments.omp).resolve() != QUALIFICATION_OMP.resolve():
        raise ValueError("qualification requires the fixed mock OMP fixture")
    if arguments.auth_broker_url not in (None, FIXED_BROKER_URL):
        raise ValueError(f"auth broker must be exactly {FIXED_BROKER_URL}")
    has_candidate_repository = arguments.candidate_maven_repository is not None
    has_candidate_version = arguments.candidate_version is not None
    if arguments.release_candidate and not arguments.execute_prepared:
        if not has_candidate_repository or not has_candidate_version:
            raise ValueError(
                "release preparation requires candidate Maven repository and version")
    elif has_candidate_repository or has_candidate_version:
        raise ValueError(
            "candidate Maven repository and version are release-preparation inputs only")
    if (not arguments.dry_run and not arguments.prepare_only
            and not arguments.qualification
            and arguments.auth_broker_url != FIXED_BROKER_URL):
        raise ValueError(
            f"measured runs require --auth-broker-url {FIXED_BROKER_URL}")
    if (not arguments.dry_run and not arguments.prepare_only
            and not arguments.qualification
            and shutil.which("Xvfb") is None):
        raise ValueError("measured graphical runs require Xvfb on PATH")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--model", required=True)
    parser.add_argument("--reasoning", default=FIXED_REASONING)
    parser.add_argument("--profile", default="low-confidence")
    parser.add_argument("--max-time", required=True)
    parser.add_argument("--pairs", type=int, default=None)
    parser.add_argument(
        "--auth-broker-url",
        help=(
            "required for measured runs; fixed precommitment: "
            f"{FIXED_BROKER_URL}"
        ),
    )
    parser.add_argument("--omp", default="omp", help=argparse.SUPPRESS)
    parser.add_argument("--qualification", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--release-candidate", action="store_true")
    parser.add_argument("--candidate-maven-repository", type=Path)
    parser.add_argument("--candidate-version")
    phase = parser.add_mutually_exclusive_group()
    phase.add_argument("--prepare-only", action="store_true")
    phase.add_argument("--execute-prepared", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        max_seconds = parse_duration(arguments.max_time)
        _validate_arguments(arguments, max_seconds)
        output = arguments.output.resolve()
        if arguments.dry_run and (arguments.prepare_only or arguments.execute_prepared):
            raise ValueError("--dry-run cannot be combined with a prepared execution phase")
        if arguments.execute_prepared:
            if not output.is_dir():
                raise ValueError(f"prepared output directory does not exist: {output}")
        elif output.exists():
            raise ValueError(f"output directory already exists: {output}")
        broker_token = ""
        if (not arguments.dry_run and not arguments.prepare_only
                and not arguments.qualification):
            broker_token = _load_broker_token(arguments.omp)
            _run_auth_preflight(
                arguments.omp, arguments.model, arguments.reasoning,
                arguments.auth_broker_url, broker_token)
        if arguments.execute_prepared:
            manifest_path = output / "benchmark-manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            if (manifest.get("schemaVersion") !=
                    "agentic-palisade/benchmark-manifest-v1"):
                raise ValueError("prepared benchmark manifest schema is unsupported")
            if manifest.get("preparedOnly") is not True:
                raise ValueError("benchmark manifest was not sealed for prepared execution")
            if manifest.get("releaseCandidate") != arguments.release_candidate:
                raise ValueError("prepared release-candidate mode does not match")
            if arguments.release_candidate and (
                    not manifest.get("candidateVersion")
                    or not manifest.get("candidateMavenRepositorySha256")):
                raise ValueError("prepared candidate Maven identity is missing")
            if (manifest.get("model") != arguments.model
                    or manifest.get("reasoning") != arguments.reasoning
                    or manifest.get("profile") != arguments.profile
                    or manifest.get("pairs") != arguments.pairs
                    or manifest.get("maxTimeSeconds") != max_seconds):
                raise ValueError("prepared benchmark arguments do not match")
            hashes = manifest.get("hashes", {})
            expected_hashes = {
                "prompt": sha256_bytes((BENCHMARK_ROOT / "prompts/task.md").read_bytes()),
                "corpus": hash_tree(BENCHMARK_ROOT / "corpus"),
                "template": hash_tree(BENCHMARK_ROOT / "template"),
                "protocol": sha256_bytes((BENCHMARK_ROOT / "PROTOCOL.md").read_bytes()),
            }
            if hashes != expected_hashes:
                raise ValueError("prepared benchmark inputs do not match current inputs")
            runs = [_restore_run(output, item, hashes)
                    for item in manifest.get("runs", [])]
            if len(runs) != arguments.pairs * 2:
                raise ValueError("prepared benchmark schedule is incomplete")
        else:
            output.mkdir(parents=True, exist_ok=False)
            candidate_repository = None
            candidate_repository_hash = None
            if arguments.release_candidate:
                candidate_repository, candidate_repository_hash = (
                    _candidate_repository(
                        arguments.candidate_maven_repository,
                        arguments.candidate_version))
            treatment_inputs = _treatment_inputs()
            hashes = {
                "prompt": sha256_bytes((BENCHMARK_ROOT / "prompts/task.md").read_bytes()),
                "corpus": hash_tree(BENCHMARK_ROOT / "corpus"),
                "template": hash_tree(BENCHMARK_ROOT / "template"),
                "protocol": sha256_bytes((BENCHMARK_ROOT / "PROTOCOL.md").read_bytes()),
            }
            _copy_tree(BENCHMARK_ROOT / "corpus", output / "corpus")
            _make_read_only(output / "corpus")
            _copy_tree(BENCHMARK_ROOT / "template", output / "template")
            _make_read_only(output / "template")
            runs = []
            index = 0
            for pair in range(1, arguments.pairs + 1):
                for treatment in ("baseline", "harness"):
                    runs.append(_prepare_run(
                        output, pair, treatment, index, hashes, treatment_inputs,
                        arguments.model, arguments.reasoning, arguments.profile,
                        max_seconds,
                        candidate_repository, arguments.candidate_version))
                    index += 1

            public_runs = [_public_item(item) for item in runs]
            manifest = {
                "schemaVersion": "agentic-palisade/benchmark-manifest-v1",
                "createdAt": utc_now(),
                "dryRun": arguments.dry_run,
                "preparedOnly": arguments.prepare_only,
                "releaseCandidate": arguments.release_candidate,
                "candidateVersion": arguments.candidate_version,
                "candidateMavenRepositorySha256": candidate_repository_hash,
                "model": arguments.model,
                "reasoning": arguments.reasoning,
                "profile": arguments.profile,
                "maxTimeSeconds": max_seconds,
                "rounds": FIXED_ROUNDS,
                "pairs": arguments.pairs,
                "protocolAmendment": PROTOCOL_AMENDMENT,
                "hashes": hashes,
                "treatmentCommonInstructionHash": treatment_inputs["commonHash"],
                "approvedTreatmentDifferences": [
                    "INSTRUCTIONS.md content after the Treatment appendix marker",
                    "treatments/harness overlay and bridge sources in harness workspaces",
                ],
                "runs": public_runs,
            }
            _write_exclusive_json(output / "benchmark-manifest.json", manifest)
        if arguments.dry_run or arguments.prepare_only:
            print(json.dumps({"status": "prepared", "runs": len(runs), "output": str(output)}))
            return 0

        classifications = []
        cancelled = []
        reason = None
        with ThreadPoolExecutor(max_workers=len(runs), thread_name_prefix="palisade-run") as executor:
            futures = {executor.submit(
                _run_one, output, item, arguments.omp, arguments.model,
                arguments.reasoning, arguments.max_time,
                QUALIFICATION_SECONDS if arguments.qualification else max_seconds,
                hashes, arguments.auth_broker_url or FIXED_BROKER_URL,
                broker_token, not arguments.qualification): item for item in runs}
            pending = set(futures)
            completed_runs = []
            for future in as_completed(pending):
                item = futures[future]
                try:
                    classification = future.result()
                except CancelledError:
                    pending.discard(future)
                    continue
                classifications.append(classification)
                completed_runs.append(item)
                pending.discard(future)
                if reason is None and _unrecoverable(classifications):
                    reason = _failure_reason(classifications, completed_runs)
                    for remaining in list(pending):
                        remaining.cancel()
                        cancelled_item = futures[remaining]
                        pending.discard(remaining)
                        cancelled.append({
                            "runId": cancelled_item["runId"],
                            "status": "cancelled",
                            "cancelReason": reason,
                        })
            for future in pending:
                future.cancel()
        if reason is not None:
            _write_exclusive_json(output / "cancellations.json", cancelled)
        successful = classifications.count("success")
        if reason is not None:
            print(json.dumps({
                "status": "complete-with-failures",
                "runs": len(runs),
                "successful": successful,
                "output": str(output),
            }))
            return 1
        print(json.dumps({
            "status": "complete" if successful == len(runs) else "complete-with-failures",
            "runs": len(runs),
            "successful": successful,
            "output": str(output),
        }))
        return 0 if successful == len(runs) else 1
    except (OSError, ValueError, argparse.ArgumentTypeError) as error:
        print(f"run-benchmark: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
