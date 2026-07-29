#!/usr/bin/env python3
"""End-to-end fixture tests for isolated benchmark preparation and supervision."""

import importlib.util
import hashlib
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest


HERE = Path(__file__).resolve().parent
RUNNER_PATH = HERE / "run-benchmark.py"
BENCHMARK_ROOT = HERE.parent
MODEL = "openai-codex/gpt-5.6-sol:medium"


def load_runner():
    spec = importlib.util.spec_from_file_location("run_benchmark", RUNNER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_json(path):
    return json.loads(Path(path).read_text())


def file_inventory(root):
    root = Path(root)
    return {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in root.rglob("*")
        if path.is_file()
        and not any(part in {".gradle", "build", "__pycache__"} for part in path.relative_to(root).parts)
    }


def run_cli(output, *extra):
    return subprocess.run(
        [sys.executable, str(RUNNER_PATH), "--output", str(output),
         "--model", MODEL, "--max-time", "45m", "--pairs", "3", *extra],
        cwd=BENCHMARK_ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=20,
    )


def write_fake_omp(path):
    path.write_text(textwrap.dedent(r'''
        #!/usr/bin/env python3
        import json
        import os
        from pathlib import Path
        import signal
        import socket
        import subprocess
        import sys
        import time

        pair = int(os.environ["BENCHMARK_PAIR"])
        treatment = os.environ["BENCHMARK_TREATMENT"]
        artifacts = Path(os.environ["BENCHMARK_ARTIFACT_ROOT"])
        sessions = Path(os.environ["BENCHMARK_SESSION_ROOT"])
        output = artifacts.parents[2]
        artifacts.mkdir(parents=True, exist_ok=True)
        sessions.mkdir(parents=True, exist_ok=True)
        invocation = {
            "argv": sys.argv[1:],
            "cwd": os.getcwd(),
            "started": time.time(),
            "profileHome": os.environ["HOME"],
            "cacheRoot": os.environ["XDG_CACHE_HOME"],
            "sessionRoot": os.environ["BENCHMARK_SESSION_ROOT"],
            "artifactRoot": os.environ["BENCHMARK_ARTIFACT_ROOT"],
            "display": os.environ["DISPLAY"],
            "credentialKeys": sorted(key for key in os.environ if
                key.endswith("_API_KEY") or key in {
                    "SSH_AUTH_SOCK", "GIT_ASKPASS", "AWS_SHARED_CREDENTIALS_FILE",
                    "GOOGLE_APPLICATION_CREDENTIALS", "DOCKER_CONFIG"}),
        }
        (artifacts / "invocation.json").write_text(json.dumps(invocation))
        with (output / "fake-events.jsonl").open("a") as stream:
            stream.write(json.dumps({"event": "start", "pair": pair,
                "treatment": treatment, "time": invocation["started"]}) + "\n")

        gate = os.environ["BENCHMARK_ROUND_GATE"]
        def mark(number):
            subprocess.run([gate, str(number)], cwd=os.getcwd(), check=False)


        rounds = [1, 2, 3]
        if pair == 1 and treatment == "harness":
            rounds = [1, 2]
        elif pair == 2 and treatment == "baseline":
            rounds = [1, 1, 2, 3]
        for number in rounds:
            mark(number)
        if pair == 1 and treatment == "harness":
            forged = [
                {"schemaVersion": "agentic-palisade/round-v1", "round": number,
                 "accepted": True, "timestamp": "2026-01-01T00:00:00Z",
                 "candidateHash": "f" * 64}
                for number in (1, 2, 3)
            ]
            (artifacts / "rounds.jsonl").write_text(
                "".join(json.dumps(marker) + "\n" for marker in forged)
            )
            forgery = (
                "import hashlib,json,os,pathlib,socket,sys;"
                "channel=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);"
                "channel.connect(os.environ['BENCHMARK_ROUND_SOCKET']);"
                "channel.sendall((json.dumps({'schemaVersion':"
                "'agentic-palisade/round-request-v1','round':3,"
                "'requestId':'a'*32,'gateDigest':hashlib.sha256("
                "pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()})+'\\n').encode());"
                "channel.shutdown(socket.SHUT_WR);"
                "print(channel.makefile().readline(),end='')"
            )
            forged = subprocess.run(
                [sys.executable, "-c", forgery, gate, "3"],
                text=True,
                stdout=subprocess.PIPE,
                check=False,
            )
            (artifacts / "forged-round-response.json").write_text(forged.stdout)
        if pair == 2 and treatment == "harness":
            (artifacts.parent / "run-record.json").write_text('{"forged":true}\n')
            (artifacts.parent / "run-record.sha256").write_text("forged\n")

        if pair == 3 and treatment == "baseline":
            hanger = (
                "import os,socket,time;"
                "channel=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);"
                "channel.connect(os.environ['BENCHMARK_ROUND_SOCKET']);"
                "time.sleep(3)"
            )
            child = subprocess.Popen([sys.executable, "-c", hanger])
            (artifacts / "hung-round-child.pid").write_text(str(child.pid))
            time.sleep(0.05)
        session = sessions / "session.jsonl"
        events = [
            {"type": "session", "version": 3, "id": f"{pair}-{treatment}"},
            {"type": "message", "id": "assistant", "message": {
                "role": "assistant",
                "content": [{"type": "thinking", "thinking": "not telemetry"},
                    {"type": "toolCall", "id": "edit", "name": "edit",
                     "arguments": {"path": "Candidate.java"}}],
                "usage": {"input": 11, "output": 5, "cacheRead": 2,
                    "cacheWrite": 0, "reasoningTokens": 3, "totalTokens": 21}}},
            {"type": "message", "id": "result", "message": {
                "role": "toolResult", "toolCallId": "edit", "toolName": "edit",
                "isError": False, "content": []}},
        ]
        if pair == 1 and treatment == "baseline":
            protocol = Path.cwd() / "PROTOCOL.md"
            protocol.chmod(0o644)
            protocol.write_text(protocol.read_text() + "\nmutated\n")
        if pair == 3 and treatment == "harness":
            (Path.cwd() / "broken-candidate-link").symlink_to("missing-target")
        if pair == 3 and treatment == "harness":
            session.write_text(json.dumps(events[0]) + "\n{\"type\":\"message\"")
        else:
            session.write_text("".join(json.dumps(event) + "\n" for event in events))
        time.sleep(0.15)
        if pair == 2 and treatment == "harness":
            sys.exit(7)
        print(json.dumps({"type": "result", "ok": True}))
    ''').lstrip())
    path.chmod(0o755)


class DryRunTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = load_runner()

    def test_prepares_three_symmetric_pairs_in_unique_immutable_roots(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "prepared"
            completed = run_cli(output, "--dry-run")
            self.assertEqual(completed.returncode, 0, completed.stderr)

            manifest_path = output / "benchmark-manifest.json"
            manifest = read_json(manifest_path)
            self.assertEqual(len(manifest["runs"]), 6)
            self.assertEqual(
                {(run["pair"], run["treatment"]) for run in manifest["runs"]},
                {(pair, treatment) for pair in (1, 2, 3)
                 for treatment in ("baseline", "harness")},
            )
            self.assertEqual(manifest["model"], MODEL)
            self.assertEqual(manifest["reasoning"], "medium")
            self.assertEqual(manifest["rounds"], 3)
            self.assertEqual(manifest["maxTimeSeconds"], 2700)

            ids = [run["runId"] for run in manifest["runs"]]
            self.assertEqual(len(set(ids)), 6)
            for field in ("workspace", "profileRoot", "cacheRoot", "sessionRoot", "artifactRoot"):
                values = [run[field] for run in manifest["runs"]]
                self.assertEqual(len(set(values)), 6, field)
                self.assertTrue(all((output / value).exists() for value in values), field)

            candidate_hashes = {run["initialCandidateHash"] for run in manifest["runs"]}
            self.assertEqual(len(candidate_hashes), 1)
            for pair in (1, 2, 3):
                runs = {run["treatment"]: run for run in manifest["runs"] if run["pair"] == pair}
                baseline = output / runs["baseline"]["workspace"]
                harness = output / runs["harness"]["workspace"]
                baseline_common, baseline_appendix = (baseline / "INSTRUCTIONS.md").read_text().split(
                    "## Treatment appendix\n", 1)
                harness_common, harness_appendix = (harness / "INSTRUCTIONS.md").read_text().split(
                    "## Treatment appendix\n", 1)
                self.assertEqual(baseline_common, harness_common)
                self.assertNotEqual(baseline_appendix, harness_appendix)
                self.assertFalse((baseline.parent / "treatments" / "harness").exists())
                self.assertTrue((harness.parent / "treatments" / "harness" / "build-overlay.gradle.kts").is_file())
                self.assertEqual(
                    self.runner.hash_candidate(baseline),
                    self.runner.hash_candidate(harness),
                )
                baseline_files = file_inventory(baseline.parent)
                harness_files = file_inventory(harness.parent)
                instruction = "template/INSTRUCTIONS.md"
                self.assertEqual(
                    {path: content for path, content in baseline_files.items() if path != instruction},
                    {path: content for path, content in harness_files.items()
                     if path != instruction and not path.startswith("treatments/harness/")},
                )
                expected_overlay = {
                    "treatments/harness/" + path
                    for path in file_inventory(BENCHMARK_ROOT / "treatments/harness")
                }
                self.assertEqual(
                    {path for path in harness_files if path.startswith("treatments/harness/")},
                    expected_overlay,
                )

            self.assertEqual(stat.S_IMODE(manifest_path.stat().st_mode), 0o444)
            first_input = output / manifest["runs"][0]["inputManifest"]
            self.assertEqual(stat.S_IMODE(first_input.stat().st_mode), 0o444)
            original = read_json(first_input)["hashes"]["initialCandidate"]
            workspace = output / manifest["runs"][0]["workspace"]
            (workspace / "src/main/java/benchmark/palisade/Mutation.java").write_text("class Mutation {}\n")
            self.assertNotEqual(self.runner.hash_candidate(workspace), original)

    def test_refuses_an_existing_output_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "existing"
            output.mkdir()
            completed = run_cli(output, "--dry-run")
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("already exists", completed.stderr)

    def test_detects_any_protected_input_drift(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "prepared"
            completed = run_cli(output, "--dry-run")
            self.assertEqual(completed.returncode, 0, completed.stderr)
            manifest = read_json(output / "benchmark-manifest.json")
            run = manifest["runs"][0]
            workspace = output / run["workspace"]
            expected = read_json(output / run["inputManifest"])["hashes"]
            protected_paths = (
                workspace / "INSTRUCTIONS.md",
                workspace / "PROTOCOL.md",
                workspace / "corpus/spec.json",
            )
            for protected in protected_paths:
                with self.subTest(path=protected.name):
                    original = protected.read_bytes()
                    protected.chmod(0o644)
                    protected.write_bytes(original + b"\n")
                    self.assertTrue(self.runner.verify_protected_inputs(workspace, expected))
                    protected.write_bytes(original)

            harness_run = next(
                item for item in manifest["runs"]
                if item["pair"] == 1 and item["treatment"] == "harness"
            )
            harness_workspace = output / harness_run["workspace"]
            harness_expected = read_json(output / harness_run["inputManifest"])["hashes"]
            overlay = harness_workspace.parent / "treatments/harness/build-overlay.gradle.kts"
            original_overlay = overlay.read_bytes()
            overlay.chmod(0o644)
            overlay.write_bytes(original_overlay + b"\n")
            self.assertTrue(
                self.runner.verify_protected_inputs(harness_workspace, harness_expected)
            )

    def test_rejects_short_deadline_even_with_a_custom_omp_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fake = root / "fake-omp"
            write_fake_omp(fake)
            completed = subprocess.run(
                [sys.executable, str(RUNNER_PATH), "--output", str(root / "out"),
                 "--model", MODEL, "--max-time", "500ms", "--pairs", "3",
                 "--omp", str(fake), "--dry-run"],
                cwd=BENCHMARK_ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=10,
            )
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("exactly --max-time 45m", completed.stderr)


class SupervisionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = load_runner()

    def test_runs_six_concurrently_with_fixed_isolation_and_retains_failures(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fake = root / "fake-omp"
            write_fake_omp(fake)
            output = root / "outcomes"
            completed = subprocess.run(
                [sys.executable, str(RUNNER_PATH), "--output", str(output),
                 "--model", MODEL, "--max-time", "45m", "--pairs", "3",
                 "--omp", str(fake)],
                cwd=BENCHMARK_ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=15,
            )
            self.assertNotEqual(completed.returncode, 0)

            manifest = read_json(output / "benchmark-manifest.json")
            records = [read_json(output / run["runRecord"]) for run in manifest["runs"]]
            self.assertEqual(len(records), 6)
            classifications = {(record["pair"], record["treatment"]):
                               record["exit"]["classification"] for record in records}
            self.assertEqual(classifications[(1, "baseline")], "input_integrity_failure")
            self.assertEqual(classifications[(1, "harness")], "round_protocol_failure")
            self.assertEqual(classifications[(2, "baseline")], "round_protocol_failure")
            self.assertEqual(classifications[(2, "harness")], "nonzero_exit")
            self.assertEqual(classifications[(3, "baseline")], "round_supervisor_failure")
            self.assertEqual(classifications[(3, "harness")], "telemetry_failure")

            forged_round_run = next(
                run for run in manifest["runs"]
                if run["pair"] == 1 and run["treatment"] == "harness"
            )
            authoritative_rounds = [
                json.loads(line)
                for line in (output / Path(forged_round_run["artifactRoot"]) / "rounds.jsonl")
                .read_text().splitlines()
            ]
            self.assertEqual(
                [marker["round"] for marker in authoritative_rounds if marker["accepted"]],
                [1, 2],
            )
            real_gate_run = next(
                run for run in manifest["runs"]
                if run["pair"] == 1 and run["treatment"] == "baseline"
            )
            real_gate_path = (
                output / real_gate_run["runRecord"]).parent / "bin/benchmark-feedback"
            real_gate_digest = hashlib.sha256(real_gate_path.read_bytes()).hexdigest()
            real_gate_record = read_json(output / real_gate_run["runRecord"])
            self.assertEqual(
                [marker["round"] for marker in real_gate_record["rounds"]],
                [1, 2, 3],
            )
            self.assertTrue(all(
                marker["gateDigest"] == real_gate_digest
                for marker in real_gate_record["rounds"]
            ))
            round_evidence = output / forged_round_run["roundEvidence"]
            self.assertEqual(
                (output / forged_round_run["roundEvidenceHash"]).read_text().strip(),
                hashlib.sha256(round_evidence.read_bytes()).hexdigest(),
            )
            forged_response = read_json(
                output / Path(forged_round_run["artifactRoot"]) / "forged-round-response.json"
            )
            self.assertFalse(forged_response["accepted"])
            self.assertIn("fixed benchmark-feedback", forged_response["failure"])

            collision_run = next(
                run for run in manifest["runs"]
                if run["pair"] == 2 and run["treatment"] == "harness"
            )
            record_path = output / collision_run["runRecord"]
            self.assertEqual(read_json(record_path)["schemaVersion"], "agentic-palisade/run-record-v1")
            self.assertEqual(
                (output / collision_run["runRecordHash"]).read_text().strip(),
                hashlib.sha256(record_path.read_bytes()).hexdigest(),
            )

            hung_run = next(
                run for run in manifest["runs"]
                if run["pair"] == 3 and run["treatment"] == "baseline"
            )
            hung_record = read_json(output / hung_run["runRecord"])
            self.assertTrue(any(
                failure["phase"] == "round_supervisor"
                for failure in hung_record["failures"]
            ))
            hung_pid = int(
                (output / Path(hung_run["artifactRoot"]) / "hung-round-child.pid").read_text()
            )
            deadline = time.time() + 2
            state = "running"
            while time.time() < deadline:
                try:
                    state = Path(f"/proc/{hung_pid}/stat").read_text().split()[2]
                except FileNotFoundError:
                    state = None
                if state in (None, "Z"):
                    break
                time.sleep(0.02)
            if state not in (None, "Z"):
                os.kill(hung_pid, signal.SIGKILL)
            self.assertIn(state, (None, "Z"))

            invocations = [read_json(output / Path(run["artifactRoot"]) / "invocation.json")
                           for run in manifest["runs"]]
            self.assertEqual(len({entry["profileHome"] for entry in invocations}), 6)
            self.assertEqual(len({entry["cacheRoot"] for entry in invocations}), 6)
            self.assertEqual(len({entry["sessionRoot"] for entry in invocations}), 6)
            self.assertEqual(len({entry["artifactRoot"] for entry in invocations}), 6)
            self.assertEqual(len({entry["display"] for entry in invocations}), 6)
            self.assertTrue(all(not entry["credentialKeys"] for entry in invocations))
            for entry in invocations:
                args = entry["argv"]
                self.assertIn("--mode", args)
                self.assertIn("json", args)
                self.assertIn("--print", args)
                self.assertIn("--auto-approve", args)
                self.assertIn("--approval-mode", args)
                self.assertIn("yolo", args)
                self.assertIn("--max-time", args)
                self.assertIn("45m", args)
                self.assertIn("--cwd", args)
                self.assertIn("--session-dir", args)
                self.assertIn("--profile", args)
                self.assertIn("--tools", args)

            starts = [json.loads(line)["time"]
                      for line in (output / "fake-events.jsonl").read_text().splitlines()]
            self.assertEqual(len(starts), 6)
            self.assertLess(max(starts) - min(starts), 0.25)


            malformed_run = next(run for run in manifest["runs"]
                                 if run["pair"] == 3 and run["treatment"] == "harness")
            raw_session = output / Path(malformed_run["sessionRoot"]) / "session.jsonl"
            self.assertTrue(raw_session.read_bytes().endswith(b'"message"'))
            self.assertTrue((output / malformed_run["runRecord"]).is_file())
            malformed_record = read_json(output / malformed_run["runRecord"])
            self.assertIsNone(malformed_record["hashes"]["finalCandidate"])
            self.assertTrue(any(
                failure["phase"] == "final_candidate_hash"
                for failure in malformed_record["failures"]
            ))

            self.assertEqual(len(invocations), 6)
            self.assertTrue(all(len(record["rounds"]) <= 3 for record in records))

    def test_terminates_the_entire_process_group_after_timeout(self):
        with tempfile.TemporaryDirectory() as temporary:
            pid_path = Path(temporary) / "child.pid"
            program = (
                "import pathlib,signal,subprocess,sys,time;"
                "signal.signal(signal.SIGTERM,signal.SIG_IGN);"
                "child=subprocess.Popen([sys.executable,'-c',"
                "'import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(60)']);"
                f"pathlib.Path({str(pid_path)!r}).write_text(str(child.pid));"
                "time.sleep(60)"
            )
            process = subprocess.Popen(
                [sys.executable, "-c", program],
                start_new_session=True,
            )
            deadline = time.time() + 2
            while time.time() < deadline and not pid_path.exists():
                time.sleep(0.01)
            self.assertTrue(pid_path.exists())
            child_pid = int(pid_path.read_text())

            self.runner.terminate_process_group(process, grace_seconds=0.05)

            deadline = time.time() + 2
            state = "running"
            while time.time() < deadline:
                try:
                    state = Path(f"/proc/{child_pid}/stat").read_text().split()[2]
                except FileNotFoundError:
                    state = None
                if state in (None, "Z"):
                    break
                time.sleep(0.02)
            self.assertIn(state, (None, "Z"))

    def test_classifies_signal_termination_as_crash(self):
        self.assertEqual(self.runner.classify_process_exit(-signal.SIGSEGV, False), "crashed")


if __name__ == "__main__":
    unittest.main()
