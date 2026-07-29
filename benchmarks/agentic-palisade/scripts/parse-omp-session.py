#!/usr/bin/env python3
"""Parse stable OMP v3 JSONL exports without inspecting hidden reasoning."""

import argparse
from collections import Counter
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
HEX_256 = re.compile(r"[0-9a-f]{64}\Z")


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


def parse_omp_session(path):
    """Return provider usage and observable tool telemetry from one OMP export."""
    records = _read_jsonl(path)
    session_headers = [record for record in records if record.get("type") == "session"]
    if len(session_headers) != 1:
        raise TelemetryError(f"expected one session header, found {len(session_headers)}")
    if session_headers[0].get("version") != 3:
        raise TelemetryError(f"unsupported OMP session version: {session_headers[0].get('version')!r}")

    usage_rows = []
    tool_calls = {}
    tool_counts = Counter()
    builds = 0
    launches = 0
    screenshots = 0

    for record_number, record in enumerate(records, start=1):
        event_type = record.get("type")
        if not isinstance(event_type, str):
            raise TelemetryError(f"record {record_number} has no string type")
        if event_type != "message":
            continue
        message = record.get("message")
        if not isinstance(message, dict):
            raise TelemetryError(f"message record {record_number} has no message object")
        role = message.get("role")
        if role == "assistant":
            usage = message.get("usage")
            if usage is not None:
                if not isinstance(usage, dict):
                    raise TelemetryError(f"assistant usage at record {record_number} is not an object")
                usage_rows.append(usage)
            content = message.get("content", [])
            if not isinstance(content, list):
                raise TelemetryError(f"assistant content at record {record_number} is not an array")
            for item in content:
                if not isinstance(item, dict):
                    raise TelemetryError(f"assistant content at record {record_number} is not an object")
                if item.get("type") != "toolCall":
                    continue
                call_id = item.get("id")
                name = item.get("name")
                arguments = item.get("arguments")
                if not isinstance(call_id, str) or not call_id or call_id in tool_calls:
                    raise TelemetryError(f"invalid or duplicate tool call id at record {record_number}")
                if not isinstance(name, str) or not name:
                    raise TelemetryError(f"invalid tool name at record {record_number}")
                if not isinstance(arguments, dict):
                    raise TelemetryError(f"tool arguments at record {record_number} are not an object")
                tool_calls[call_id] = name
                tool_counts[name] += 1
                if name == "bash":
                    command = _command(arguments)
                    has_gradle = "gradlew" in command or re.search(r"(?:^|\s)gradle(?:\s|$)", command)
                    if has_gradle and BUILD_TASK.search(command):
                        builds += 1
                    if has_gradle and LAUNCH_TASK.search(command):
                        launches += 1
                    screenshots += len(SCREENSHOT_OPERATION.findall(command))
        elif role == "toolResult":
            call_id = message.get("toolCallId")
            name = message.get("toolName")
            if not isinstance(call_id, str) or not isinstance(name, str):
                raise TelemetryError(f"invalid tool result at record {record_number}")
        elif not isinstance(role, str):
            raise TelemetryError(f"message role at record {record_number} is not a string")

    failed_operations = []
    seen_results = set()
    for record_number, record in enumerate(records, start=1):
        if record.get("type") != "message":
            continue
        message = record["message"]
        if message.get("role") != "toolResult":
            continue
        call_id = message["toolCallId"]
        name = message["toolName"]
        if call_id not in tool_calls or tool_calls[call_id] != name or call_id in seen_results:
            raise TelemetryError(f"unmatched or duplicate tool result at record {record_number}")
        seen_results.add(call_id)
        is_error = message.get("isError")
        if not isinstance(is_error, bool):
            raise TelemetryError(f"tool result error flag at record {record_number} is not boolean")
        if is_error:
            failed_operations.append({"toolCallId": call_id, "name": name})
    unfinished = sorted(set(tool_calls) - seen_results)
    if unfinished:
        raise TelemetryError(f"unfinished tool calls: {', '.join(unfinished)}")

    tokens = {}
    for public_name, provider_name in TOKEN_FIELDS.items():
        if not usage_rows or any(provider_name not in usage for usage in usage_rows):
            tokens[public_name] = {"status": "unavailable", "value": None}
            continue
        total = sum(
            _nonnegative_integer(usage[provider_name], f"usage.{provider_name}")
            for usage in usage_rows
        )
        tokens[public_name] = {"status": "available", "value": total}

    return {
        "tokens": tokens,
        "toolCalls": dict(sorted(tool_counts.items())),
        "edits": sum(tool_counts[name] for name in EDIT_TOOLS),
        "builds": builds,
        "launches": launches,
        "screenshots": screenshots,
        "failedOperations": failed_operations,
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
