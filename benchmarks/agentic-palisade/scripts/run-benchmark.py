#!/usr/bin/env python3
"""Prepare and supervise the six precommitted Agentic Palisade OMP runs."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time
import threading
import uuid


SCRIPT_ROOT = Path(__file__).resolve().parent
BENCHMARK_ROOT = SCRIPT_ROOT.parent
REPOSITORY_ROOT = BENCHMARK_ROOT.parent.parent
FIXED_MODEL = "openai-codex/gpt-5.6-sol:medium"
FIXED_REASONING = "medium"
FIXED_PAIRS = 3
FIXED_ROUNDS = 3
FIXED_SECONDS = 45 * 60
QUALIFICATION_SECONDS = 1
QUALIFICATION_OMP = BENCHMARK_ROOT / "fixtures/mock-omp.py"
TOOL_ALLOWLIST = "read,write,edit,bash,grep,glob"
GENERATED_NAMES = {".gradle", "build", "__pycache__"}
CANDIDATE_INPUT_NAMES = {"INSTRUCTIONS.md", "PROTOCOL.md", "corpus"}
DURATION = re.compile(r"(?P<amount>[1-9][0-9]*)(?P<unit>ms|s|m|h)\Z")
CREDENTIAL_KEYS = {
    "SSH_AUTH_SOCK", "GIT_ASKPASS", "SSH_ASKPASS", "AWS_PROFILE",
    "AWS_SHARED_CREDENTIALS_FILE", "GOOGLE_APPLICATION_CREDENTIALS",
    "DOCKER_CONFIG", "KUBECONFIG", "NETRC",
}
SAFE_PARENT_ENV = {
    "JAVA_HOME", "JDK_HOME", "LANG", "LC_ALL", "LC_CTYPE", "LD_LIBRARY_PATH",
    "PATH", "SHELL", "TERM", "TZ",
}


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


def _empty_telemetry():
    return {
        "tokens": {name: {"status": "unavailable", "value": None}
                   for name in ("input", "output", "cacheRead", "cacheWrite", "reasoning")},
        "toolCalls": {},
        "edits": 0,
        "builds": 0,
        "launches": 0,
        "screenshots": 0,
        "failedOperations": [],
    }


def _relative(output, path):
    return Path(path).relative_to(output).as_posix()


def _prepare_run(output, pair, treatment, index, hashes, treatment_inputs):
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
        treatment_overlay_hash = hash_tree(
            benchmark / "treatments/harness", ignored_names=frozenset())
        _make_read_only(benchmark / "treatments/harness")

    prompt_path = run_dir / "task.md"
    shutil.copy2(BENCHMARK_ROOT / "prompts/task.md", prompt_path)
    config_path = run_dir / "omp-config.yml"
    config_path.write_text("{}\n", encoding="utf-8")
    gate_path = binary_root / "benchmark-feedback"
    gate_path.write_text(ROUND_GATE, encoding="utf-8")
    gate_path.chmod(0o555)
    prompt_path.chmod(0o444)
    config_path.chmod(0o444)
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
        "model": FIXED_MODEL,
        "reasoning": FIXED_REASONING,
        "maxTimeSeconds": FIXED_SECONDS,
        "rounds": FIXED_ROUNDS,
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
        "config": config_path,
        "roundLog": round_log,
        "roundHash": round_hash,
        "roundSocket": round_socket,
        "gate": gate_path,
    }
    return item


def _public_item(item):
    return {key: value for key, value in item.items() if key != "_runtime"}


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



def _omp_command(omp, item, model, max_time_text):
    runtime = item["_runtime"]
    return [
        omp,
        "--model", model,
        "--thinking", FIXED_REASONING,
        "--profile", item["runId"],
        "--session-dir", str(runtime["sessionRoot"]),
        "--config", str(runtime["config"]),
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


def _discover_session(session_root):
    candidates = sorted(
        path for path in Path(session_root).rglob("*.jsonl") if path.is_file())
    if len(candidates) != 1:
        raise TELEMETRY.TelemetryError(
            f"expected exactly one OMP session export, found {len(candidates)}")
    return candidates[0]

def _run_one(output, item, omp, model, max_time_text, max_time_seconds, hashes):
    runtime = item["_runtime"]
    started_at = utc_now()
    started = time.monotonic()
    command = _omp_command(omp, item, model, max_time_text)
    environment = _sanitized_environment(item)
    stdout_path = runtime["logRoot"] / "omp.stdout.jsonl"
    stderr_path = runtime["logRoot"] / "omp.stderr.log"
    timed_out = False
    return_code = None
    launch_error = None
    supervisor_failures = []
    round_supervisor = None
    try:
        round_supervisor = RoundSupervisor(
            runtime["roundSocket"], runtime["workspace"], runtime["gate"])
        round_supervisor.start()
    except (OSError, RuntimeError) as error:
        supervisor_failures.append(f"round supervisor failed to start: {error}")
    with stdout_path.open("xb") as stdout, stderr_path.open("xb") as stderr:
        if not supervisor_failures:
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
                return_code, timed_out, shutdown_error = wait_for_process_group(
                    process, max_time_seconds)
                if shutdown_error:
                    supervisor_failures.append(shutdown_error)
            except OSError as error:
                launch_error = str(error)
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
    if timed_out:
        failures.append({"phase": "deadline", "message": f"exceeded {max_time_text}"})
    elif return_code not in (0, None):
        failures.append({"phase": "omp_exit", "message": f"exit status {return_code}"})

    telemetry = _empty_telemetry()
    telemetry_error = None
    session_path = None
    try:
        session_path = _discover_session(runtime["sessionRoot"])
        telemetry = TELEMETRY.parse_omp_session(session_path)
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

    record = {
        "schemaVersion": "agentic-palisade/run-record-v1",
        "runId": item["runId"],
        "pair": item["pair"],
        "treatment": item["treatment"],
        "model": model,
        "reasoning": FIXED_REASONING,
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
    if arguments.model != FIXED_MODEL:
        raise ValueError(f"model must be exactly {FIXED_MODEL}")
    if arguments.pairs != FIXED_PAIRS:
        raise ValueError(f"pairs must be exactly {FIXED_PAIRS}")
    if max_seconds != FIXED_SECONDS:
        raise ValueError("measured runs must use exactly --max-time 45m")
    if arguments.qualification and Path(arguments.omp).resolve() != QUALIFICATION_OMP.resolve():
        raise ValueError("qualification requires the fixed mock OMP fixture")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--model", required=True)
    parser.add_argument("--max-time", required=True)
    parser.add_argument("--pairs", required=True, type=int)
    parser.add_argument("--omp", default="omp", help=argparse.SUPPRESS)
    parser.add_argument("--qualification", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--dry-run", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        max_seconds = parse_duration(arguments.max_time)
        _validate_arguments(arguments, max_seconds)
        output = arguments.output.resolve()
        if output.exists():
            raise ValueError(f"output directory already exists: {output}")
        output.mkdir(parents=True, exist_ok=False)

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
        for pair in range(1, FIXED_PAIRS + 1):
            for treatment in ("baseline", "harness"):
                runs.append(_prepare_run(
                    output, pair, treatment, index, hashes, treatment_inputs))
                index += 1

        public_runs = [_public_item(item) for item in runs]
        manifest = {
            "schemaVersion": "agentic-palisade/benchmark-manifest-v1",
            "createdAt": utc_now(),
            "dryRun": arguments.dry_run,
            "model": arguments.model,
            "reasoning": FIXED_REASONING,
            "maxTimeSeconds": FIXED_SECONDS,
            "rounds": FIXED_ROUNDS,
            "pairs": FIXED_PAIRS,
            "hashes": hashes,
            "treatmentCommonInstructionHash": treatment_inputs["commonHash"],
            "approvedTreatmentDifferences": [
                "INSTRUCTIONS.md content after the Treatment appendix marker",
                "treatments/harness overlay and bridge sources in harness workspaces",
            ],
            "runs": public_runs,
        }
        _write_exclusive_json(output / "benchmark-manifest.json", manifest)
        if arguments.dry_run:
            print(json.dumps({"status": "prepared", "runs": len(runs), "output": str(output)}))
            return 0

        classifications = []
        with ThreadPoolExecutor(max_workers=len(runs), thread_name_prefix="palisade-run") as executor:
            futures = [executor.submit(
                _run_one, output, item, arguments.omp, arguments.model,
                arguments.max_time,
                QUALIFICATION_SECONDS if arguments.qualification else max_seconds,
                hashes) for item in runs]
            for future in as_completed(futures):
                classifications.append(future.result())
        successful = classifications.count("success")
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
