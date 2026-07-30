#!/usr/bin/env python3
"""Parse stable OMP v3 JSONL exports without inspecting hidden reasoning."""

import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys


TOKEN_FIELDS = {
    "input": "input",
    "output": "output",
    "cacheRead": "cacheRead",
    "cacheWrite": "cacheWrite",
    "reasoning": "reasoningTokens",
}
EDIT_TOOLS = {"edit", "write", "ast_edit"}
BUILD_TASK = re.compile(r"(?:^|\s)(?:build|assemble|classes|compileJava|test|check|jar)(?:\s|$)")
LAUNCH_TASK = re.compile(r"(?:^|\s)(?:run|runCandidate|launch)(?:\s|$)")
SCREENSHOT_OPERATION = re.compile(r"(?:ui_screenshot|(?:^|\s)capture(?:\s|$))")
DIRECT_CAPTURE_OPERATION = re.compile(r"\bui_(?:screenshot|inspect_compare)\b")
LAUNCHER_CAPTURE_OPERATION = re.compile(r"(?:^|\s)capture(?:\s|$)")
HEX_256 = re.compile(r"[0-9a-f]{64}\Z")


def _load_trace_module():
    path = Path(__file__).resolve().parent / "trace-taxonomy.py"
    spec = importlib.util.spec_from_file_location(
        "agentic_palisade_trace_taxonomy", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


TRACE = _load_trace_module()


class TelemetryError(ValueError):
    """The exported session cannot be interpreted without guessing."""


class RoundProtocolError(TelemetryError):
    """The round gate did not record exactly the precommitted sequence."""

    def __init__(self, message, markers=None):
        super().__init__(message)
        self.markers = list(markers or [])


def _read_jsonl(path, error_type=TelemetryError):
    path = Path(path)
    try:
        content = path.read_bytes()
    except OSError as error:
        raise error_type(f"cannot read {path}: {error}") from error
    if not content:
        raise error_type(f"empty JSONL export: {path}")
    if not content.endswith(b"\n"):
        raise error_type(f"truncated JSONL export (missing final newline): {path}")

    records = []
    for line_number, raw_line in enumerate(content.splitlines(), start=1):
        if not raw_line.strip():
            raise error_type(f"blank JSONL record at line {line_number}: {path}")
        try:
            record = json.loads(raw_line)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise error_type(f"malformed JSON at line {line_number}: {path}: {error}") from error
        if not isinstance(record, dict):
            raise error_type(f"record {line_number} is not an object: {path}")
        records.append(record)
    return records


def _nonnegative_integer(value, label):
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise TelemetryError(f"{label} must be a non-negative integer")
    return value


def _command(arguments):
    if not isinstance(arguments, dict):
        raise TelemetryError("tool call arguments must be an object")
    command = arguments.get("command", "")
    if command is None:
        return ""
    if not isinstance(command, str):
        raise TelemetryError("bash command must be a string")
    return command


def parse_omp_session(path, workspace=None, identity=None):
    """Return provider usage plus normalized, digest-bound observable events."""
    path = Path(path)
    records = _read_jsonl(path)
    session_headers = [
        record for record in records if record.get("type") == "session"]
    if len(session_headers) != 1:
        raise TelemetryError(
            f"expected one session header, found {len(session_headers)}")
    header = session_headers[0]
    if header.get("version") != 3:
        raise TelemetryError(
            f"unsupported OMP session version: {header.get('version')!r}")
    session_id = header.get("id")
    if not isinstance(session_id, str) or not session_id:
        raise TelemetryError("session header has no stable identity")
    identity = identity or {}
    batch_id = identity.get("batchId", "unavailable")
    run_id = identity.get("runId", "unavailable")
    workspace = Path(workspace) if workspace is not None else path.parent

    usage_rows = []
    tool_calls = {}
    tool_arguments = {}
    tool_counts = Counter()
    request_event_ids = {}
    trace_events = []
    capture_calls = {}
    capture_attempts = []
    builds = 0
    launches = 0
    screenshots = 0

    for record_number, record in enumerate(records, start=1):
        event_type = record.get("type")
        if not isinstance(event_type, str):
            raise TelemetryError(
                f"record {record_number} has no string type")
        if event_type != "message":
            continue
        message = record.get("message")
        if not isinstance(message, dict):
            raise TelemetryError(
                f"message record {record_number} has no message object")
        role = message.get("role")
        if role == "toolResult":
            if (not isinstance(message.get("toolCallId"), str)
                    or not isinstance(message.get("toolName"), str)):
                raise TelemetryError(
                    f"invalid tool result at record {record_number}")
            continue
        if role != "assistant":
            if not isinstance(role, str):
                raise TelemetryError(
                    f"message role at record {record_number} is not a string")
            continue
        usage = message.get("usage")
        if usage is not None:
            if not isinstance(usage, dict):
                raise TelemetryError(
                    f"assistant usage at record {record_number} is not an object")
            usage_rows.append(usage)
        content = message.get("content", [])
        if not isinstance(content, list):
            raise TelemetryError(
                f"assistant content at record {record_number} is not an array")
        for item in content:
            if not isinstance(item, dict):
                raise TelemetryError(
                    f"assistant content at record {record_number} is not an object")
            if item.get("type") != "toolCall":
                continue
            call_id = item.get("id")
            name = item.get("name")
            arguments = item.get("arguments")
            if (not isinstance(call_id, str) or not call_id
                    or call_id in tool_calls):
                raise TelemetryError(
                    f"invalid or duplicate tool call id at record {record_number}")
            if not isinstance(name, str) or not name:
                raise TelemetryError(
                    f"invalid tool name at record {record_number}")
            if not isinstance(arguments, dict):
                raise TelemetryError(
                    f"tool arguments at record {record_number} is not an object")
            tool_calls[call_id] = name
            tool_arguments[call_id] = arguments
            tool_counts[name] += 1
            request = TRACE.event(
                batch_id, run_id, session_id, len(trace_events),
                "tool-request", [], operation=name,
                requestId=hashlib.sha256(
                    call_id.encode("utf-8")).hexdigest(),
                payloadSha256=hashlib.sha256(
                    TRACE.canonical_bytes(arguments)).hexdigest(),
                evidenceClass="observed")
            trace_events.append(request)
            request_event_ids[call_id] = request["eventId"]

            normalized = []
            if name in ("ui_screenshot", "ui_inspect_compare"):
                payload = {"operation": name, "arguments": arguments}
                payload_sha = hashlib.sha256(
                    TRACE.canonical_bytes(payload)).hexdigest()
                normalized.append({
                    "attemptId": hashlib.sha256(TRACE.canonical_bytes({
                        "sessionId": session_id,
                        "callId": call_id,
                        "payloadSha256": payload_sha,
                    })).hexdigest(),
                    "requestId": payload_sha,
                    "operation": name,
                    "payloadSha256": payload_sha,
                    "source": {
                        "kind": "direct-tool-call",
                        "name": name,
                        "sha256": hashlib.sha256(
                            TRACE.canonical_bytes(arguments)).hexdigest(),
                    },
                    "replayOfPayloadSha256": payload_sha,
                    "result": None,
                })
            if name == "bash":
                command = _command(arguments)
                has_gradle = (
                    "gradlew" in command
                    or re.search(r"(?:^|\s)gradle(?:\s|$)", command))
                if has_gradle and BUILD_TASK.search(command):
                    builds += 1
                if has_gradle and LAUNCH_TASK.search(command):
                    launches += 1
                screenshots += len(SCREENSHOT_OPERATION.findall(command))
                normalized.extend(TRACE.capture_attempts_from_arguments(
                    session_id, record_number, call_id, arguments, workspace))
                for occurrence in range(
                        len(LAUNCHER_CAPTURE_OPERATION.findall(command))):
                    command_sha = hashlib.sha256(
                        command.encode("utf-8")).hexdigest()
                    normalized.append({
                        "attemptId": hashlib.sha256(
                            f"{session_id}:{call_id}:launcher:{occurrence}".encode(
                                "utf-8")).hexdigest(),
                        "requestId": hashlib.sha256(
                            f"{call_id}:launcher:{occurrence}".encode(
                                "utf-8")).hexdigest(),
                        "operation": "launcher:capture",
                        "payloadSha256": command_sha,
                        "source": {
                            "kind": "literal-command",
                            "name": "command",
                            "sha256": command_sha,
                        },
                        "replayOfPayloadSha256": None,
                        "result": None,
                    })
            if normalized:
                for offset, attempt in enumerate(normalized):
                    attempt.update({
                        "batchId": batch_id,
                        "runId": run_id,
                        "sessionId": session_id,
                        "sequence": len(capture_attempts) + offset,
                    })
                    capture_event = TRACE.event(
                        batch_id, run_id, session_id, len(trace_events),
                        "capture-request", [request_event_ids[call_id]],
                        operation=attempt["operation"],
                        requestId=attempt["requestId"],
                        payloadSha256=attempt["payloadSha256"],
                        attemptId=attempt["attemptId"],
                        evidenceClass="observed")
                    trace_events.append(capture_event)
                    attempt["requestEventId"] = capture_event["eventId"]
                    attempt["resultEventId"] = None
                    attempt["outcomeEventId"] = None
                capture_calls[call_id] = normalized
                capture_attempts.extend(normalized)

    failed_operations = []
    seen_results = set()
    loop_inputs = []
    for record_number, record in enumerate(records, start=1):
        if record.get("type") != "message":
            continue
        message = record["message"]
        if message.get("role") != "toolResult":
            continue
        call_id = message["toolCallId"]
        name = message["toolName"]
        if (call_id not in tool_calls or tool_calls[call_id] != name
                or call_id in seen_results):
            raise TelemetryError(
                f"unmatched or duplicate tool result at record {record_number}")
        seen_results.add(call_id)
        is_error = message.get("isError")
        if not isinstance(is_error, bool):
            raise TelemetryError(
                f"tool result error flag at record {record_number} is not boolean")
        if is_error:
            failed_operations.append({"toolCallId": call_id, "name": name})
        visible = json.dumps(
            message.get("content", []), sort_keys=True, separators=(",", ":"))
        if is_error and (
                "invalid-arguments" in visible
                or "schema rejected" in visible.lower()):
            error_code = "invalid-arguments"
        elif is_error:
            error_code = "execution-failed"
        else:
            error_code = None
        result_event = TRACE.event(
            batch_id, run_id, session_id, len(trace_events),
            "tool-result", [request_event_ids[call_id]], operation=name,
            requestId=hashlib.sha256(call_id.encode("utf-8")).hexdigest(),
            errorClass=error_code, evidenceClass="observed")
        trace_events.append(result_event)
        progress = []
        if "artifact" in visible.lower():
            progress.append({
                "kind": "artifact", "id": result_event["eventId"]})
        if "metrics" in visible:
            progress.append({
                "kind": "evaluator", "id": result_event["eventId"]})
        loop_inputs.append({
            "sequence": len(loop_inputs),
            "operation": name,
            "intentSha256": hashlib.sha256(
                TRACE.canonical_bytes(tool_arguments[call_id])).hexdigest(),
            "errorClass": error_code,
            "progress": progress,
        })
        for attempt in capture_calls.get(call_id, []):
            attempt["resultEventId"] = result_event["eventId"]
            attempt["result"] = {
                "isError": is_error,
                "code": error_code,
                "succeeded": (
                    not is_error
                    and (
                        "screenshot-result" in visible
                        or "inspect-compare-result" in visible
                    )),
                "artifactCreated": (
                    "screenshot-result" in visible
                    or "currentArtifact" in visible
                    or attempt["operation"] == "launcher:capture"),
                "inspected": (
                    "inspect-compare-result" in visible
                    and "currentArtifact" in visible),
                "compared": bool(re.search(
                    r'\bmetrics\b.{0,4}[{\\"]', visible)),
                "stale": bool(re.search(
                    r'\bstatus\b.{0,16}\bstale\b', visible)),
            }
    unfinished = sorted(set(tool_calls) - seen_results)
    if unfinished:
        raise TelemetryError(
            f"unfinished tool calls: {', '.join(unfinished)}")

    tokens = {}
    for public_name, provider_name in TOKEN_FIELDS.items():
        if not usage_rows or any(
                provider_name not in usage for usage in usage_rows):
            tokens[public_name] = {
                "status": "unavailable", "value": None}
            continue
        total = sum(
            _nonnegative_integer(
                usage[provider_name], f"usage.{provider_name}")
            for usage in usage_rows)
        tokens[public_name] = {"status": "available", "value": total}

    lifecycle = TRACE.classify_capture_lifecycle(capture_attempts)
    capture_events = Counter({
        "attempted": 0, "schemaRejected": 0, "accepted": 0,
        "inspected": 0, "compared": 0, "stale": 0,
        "completionUsed": 0, "launcherCaptures": 0,
    })
    for attempt in capture_attempts:
        outcome = lifecycle[attempt["attemptId"]]
        outcome_event = TRACE.event(
            batch_id, run_id, session_id, len(trace_events),
            "capture-outcome",
            [attempt["requestEventId"], attempt["resultEventId"]],
            operation=attempt["operation"],
            requestId=attempt["requestId"],
            payloadSha256=attempt["payloadSha256"],
            attemptId=attempt["attemptId"],
            errorClass=attempt["result"].get("code"),
            evidenceClass="observed")
        trace_events.append(outcome_event)
        attempt["outcomeEventId"] = outcome_event["eventId"]
        states = outcome["states"]
        if outcome["channel"] == "launcher":
            capture_events["launcherCaptures"] += 1
            continue
        capture_events["attempted"] += 1
        capture_events["schemaRejected"] += "schema-rejected" in states
        capture_events["accepted"] += "succeeded" in states
        capture_events["inspected"] += "inspected" in states
        capture_events["compared"] += "compared-current" in states
        capture_events["stale"] += "stale" in states
        capture_events["completionUsed"] += "compared-current" in states

    TRACE.validate_identity_graph(trace_events, batch_id, run_id)
    attributions = {
        family: [] for family in (
            "capture", "semantic", "rendering", "workflow-loop")}
    if capture_attempts:
        capture_event_ids = []
        for attempt in capture_attempts:
            capture_event_ids.extend([
                attempt["requestEventId"],
                attempt["resultEventId"],
                attempt["outcomeEventId"],
            ])
        attributions["capture"].append(TRACE.attribution(
            "capture", "observed", "capture-lifecycle/v1",
            list(dict.fromkeys(capture_event_ids)),
            {
                "attempts": capture_events["attempted"],
                "schemaRejected": capture_events["schemaRejected"],
                "succeeded": capture_events["accepted"],
                "launcherCaptures": capture_events["launcherCaptures"],
            }))
    attributions["workflow-loop"] = [
        TRACE.attribution(
            "workflow-loop", "observed", loop["ruleId"], [], loop)
        for loop in TRACE.classify_unproductive_loops(loop_inputs)
    ]
    trace_taxonomy = {
        "schemaVersion": TRACE.SCHEMA_VERSION,
        "availability": "available",
        "parserVersion": "parse-omp-session/v2",
        "batchId": batch_id,
        "runId": run_id,
        "sessionId": session_id,
        "inputSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "sequenceBasis": (
            "two-pass OMP JSONL: request content order, result record order, "
            "then derived capture outcomes"),
        "knownExclusions": [
            "hidden reasoning",
            "payload files outside the declared workspace",
            "causal inference from association",
        ],
        "events": trace_events,
        "captureAttempts": capture_attempts,
        "captureLifecycle": lifecycle,
        "attributions": attributions,
        "joins": {
            "process": {"status": "unavailable", "identity": None},
            "rounds": {"status": "unavailable", "identities": []},
            "evaluation": {"status": "unavailable", "identity": None},
        },
    }

    return {
        "tokens": tokens,
        "toolCalls": dict(sorted(tool_counts.items())),
        "edits": sum(tool_counts[name] for name in EDIT_TOOLS),
        "builds": builds,
        "launches": launches,
        "screenshots": screenshots,
        "captureEvents": dict(capture_events),
        "failedOperations": failed_operations,
        "traceTaxonomy": trace_taxonomy,
    }


def parse_round_log(path, expected_rounds=3):
    records = _read_jsonl(path, RoundProtocolError)
    accepted = []
    for index, record in enumerate(records, start=1):
        required = {"schemaVersion", "round", "accepted", "timestamp", "candidateHash"}
        if not required.issubset(record):
            raise RoundProtocolError(f"round record {index} is missing required fields", accepted)
        if record["schemaVersion"] != "agentic-palisade/round-v1":
            raise RoundProtocolError(f"round record {index} has an unsupported schema", accepted)
        number = record["round"]
        if isinstance(number, bool) or not isinstance(number, int):
            raise RoundProtocolError(f"round record {index} has a non-integer round", accepted)
        if not isinstance(record["timestamp"], str) or not record["timestamp"]:
            raise RoundProtocolError(f"round record {index} has no timestamp", accepted)
        if not isinstance(record["candidateHash"], str) or not HEX_256.fullmatch(record["candidateHash"]):
            raise RoundProtocolError(f"round record {index} has an invalid candidate hash", accepted)
        if record["accepted"] is not True:
            raise RoundProtocolError(
                f"round gate rejected attempt {number}: {record.get('failure', 'unspecified')}", accepted)
        accepted.append(record)

    observed = [record["round"] for record in accepted]
    expected = list(range(1, expected_rounds + 1))
    if observed != expected:
        raise RoundProtocolError(f"expected rounds {expected}, observed {observed}", accepted)
    return accepted


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", type=Path)
    parser.add_argument("--round-log", type=Path)
    arguments = parser.parse_args(argv)
    try:
        result = parse_omp_session(arguments.session)
        if arguments.round_log:
            result["rounds"] = parse_round_log(arguments.round_log)
    except TelemetryError as error:
        print(json.dumps({"error": str(error)}, sort_keys=True), file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
