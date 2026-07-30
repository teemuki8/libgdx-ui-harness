#!/usr/bin/env python3
"""Closed trace taxonomy and digest-bound event graph for Agentic Palisade."""

import hashlib
import json
from pathlib import Path
import re


SCHEMA_VERSION = "agentic-palisade/trace-taxonomy-v1"
EVENT_VERSION = "agentic-palisade/trace-event-v1"
FAMILIES = {"capture", "semantic", "rendering", "workflow-loop"}
EVIDENCE_CLASSES = {"observed", "source-established-cause", "hypothesis"}
CAPTURE_OPERATIONS = {"ui_screenshot", "ui_inspect_compare"}
NDJSON_REFERENCE = re.compile(r"(?<![A-Za-z0-9_.-])([A-Za-z0-9_./-]+\.ndjson)\b")
SHA_256 = re.compile(r"[0-9a-f]{64}\Z")


class TraceTaxonomyError(ValueError):
    """Trace evidence is incomplete, inconsistent, or crosses its trust boundary."""


def canonical_bytes(value):
    return (json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ) + "\n").encode("utf-8")


def sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()


def _bounded_text(value, label):
    if not isinstance(value, str) or not value or len(value) > 16_384:
        raise TraceTaxonomyError(f"{label} must be non-empty bounded text")
    return value


def seal_event(value):
    """Return an event whose ID hashes every field except the ID itself."""
    sealed = dict(value)
    sealed.pop("eventId", None)
    sealed["eventId"] = sha256_bytes(canonical_bytes(sealed))
    return sealed


def event(batch_id, run_id, session_id, sequence, kind, parents, **fields):
    if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence < 0:
        raise TraceTaxonomyError("event sequence must be a non-negative integer")
    value = {
        "schemaVersion": EVENT_VERSION,
        "batchId": _bounded_text(batch_id, "batchId"),
        "runId": _bounded_text(run_id, "runId"),
        "sessionId": _bounded_text(session_id, "sessionId"),
        "sequence": sequence,
        "kind": _bounded_text(kind, "kind"),
        "parentEventIds": list(parents),
        **fields,
    }
    return seal_event(value)


def validate_identity_graph(events, expected_batch_id, expected_run_id):
    """Reject guessed joins: identities must be unique, sealed, same-run, and acyclic."""
    if not isinstance(events, list):
        raise TraceTaxonomyError("events must be an array")
    by_id = {}
    for item in events:
        if not isinstance(item, dict):
            raise TraceTaxonomyError("event must be an object")
        event_id = item.get("eventId")
        if not isinstance(event_id, str) or event_id in by_id:
            raise TraceTaxonomyError("duplicate or missing event identity")
        if seal_event(item)["eventId"] != event_id:
            raise TraceTaxonomyError("event digest mismatch")
        if (item.get("schemaVersion") != EVENT_VERSION
                or item.get("batchId") != expected_batch_id
                or item.get("runId") != expected_run_id):
            raise TraceTaxonomyError("cross-run or unsupported event identity")
        by_id[event_id] = item
    for item in events:
        parents = item.get("parentEventIds")
        if not isinstance(parents, list) or len(parents) != len(set(parents)):
            raise TraceTaxonomyError("invalid parent identities")
        if any(parent not in by_id for parent in parents):
            raise TraceTaxonomyError("unknown parent identity")

    visiting = set()
    visited = set()

    def visit(event_id):
        if event_id in visiting:
            raise TraceTaxonomyError("cyclic event identity graph")
        if event_id in visited:
            return
        visiting.add(event_id)
        for parent in by_id[event_id]["parentEventIds"]:
            visit(parent)
        visiting.remove(event_id)
        visited.add(event_id)

    for event_id in by_id:
        visit(event_id)
    return True


def _capture_payload(value):
    if not isinstance(value, dict):
        return None
    operation = value.get("operation") or value.get("tool") or value.get("name")
    arguments = value.get("arguments", {})
    if value.get("method") == "tools/call" and isinstance(value.get("params"), dict):
        operation = value["params"].get("name")
        arguments = value["params"].get("arguments", {})
    if operation not in CAPTURE_OPERATIONS or not isinstance(arguments, dict):
        return None
    return {"operation": operation, "arguments": arguments}


def _json_objects(text):
    decoder = json.JSONDecoder()
    index = 0
    while index < len(text):
        start = text.find("{", index)
        if start < 0:
            return
        try:
            value, end = decoder.raw_decode(text, start)
        except json.JSONDecodeError:
            index = start + 1
            continue
        yield value
        index = end


def _safe_referenced_file(workspace, relative):
    root = Path(workspace).resolve()
    candidate = root / relative
    if candidate.is_symlink():
        raise TraceTaxonomyError("referenced payload cannot be a symbolic link")
    resolved = candidate.resolve()
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise TraceTaxonomyError("referenced payload escapes workspace") from error
    if not resolved.is_file() or resolved.stat().st_size > 16 * 1024 * 1024:
        raise TraceTaxonomyError("referenced payload is missing or oversized")
    return resolved


def capture_attempts_from_arguments(
        session_id, sequence, call_id, arguments, workspace, evidence_gaps=None):
    """Normalize literal, environment-like, stdin, and referenced NDJSON payloads."""
    if not isinstance(arguments, dict):
        raise TraceTaxonomyError("tool arguments must be an object")
    session_id = _bounded_text(session_id, "sessionId")
    call_id = _bounded_text(call_id, "callId")
    sources = []
    for key, value in arguments.items():
        if not isinstance(value, str):
            continue
        source_bytes = value.encode("utf-8")
        source = {
            "kind": "argument",
            "name": key,
            "sha256": sha256_bytes(source_bytes),
        }
        for parsed in _json_objects(value):
            payload = _capture_payload(parsed)
            if payload is not None:
                sources.append((source, payload))
        if key != "command":
            continue
        for match in NDJSON_REFERENCE.finditer(value):
            relative = match.group(1)
            try:
                path = _safe_referenced_file(workspace, relative)
            except TraceTaxonomyError as error:
                if evidence_gaps is None:
                    raise
                evidence_gaps.append({
                    "toolCallId": call_id,
                    "reference": relative,
                    "code": "REFERENCED_PAYLOAD_UNAVAILABLE",
                    "message": str(error),
                })
                continue
            try:
                content = path.read_bytes()
                file_source = {
                    "kind": "referenced-ndjson",
                    "name": relative,
                    "sha256": sha256_bytes(content),
                }
                file_sources = []
                for line_number, line in enumerate(content.splitlines(), 1):
                    try:
                        parsed = json.loads(line)
                    except (UnicodeDecodeError, json.JSONDecodeError) as error:
                        raise TraceTaxonomyError(
                            f"invalid referenced NDJSON line {line_number}") from error
                    payload = _capture_payload(parsed)
                    if payload is not None:
                        file_sources.append((file_source, payload))
                sources.extend(file_sources)
            except TraceTaxonomyError as error:
                if evidence_gaps is None:
                    raise
                evidence_gaps.append({
                    "toolCallId": call_id,
                    "reference": relative,
                    "code": "REFERENCED_PAYLOAD_UNAVAILABLE",
                    "message": str(error),
                })

    attempts = []
    for occurrence, (source, payload) in enumerate(sources):
        payload_sha = sha256_bytes(canonical_bytes(payload))
        identity = {
            "sessionId": session_id,
            "sequence": sequence,
            "callId": call_id,
            "occurrence": occurrence,
            "sourceSha256": source["sha256"],
            "payloadSha256": payload_sha,
        }
        attempts.append({
            "attemptId": sha256_bytes(canonical_bytes(identity)),
            "requestId": payload_sha,
            "operation": payload["operation"],
            "payloadSha256": payload_sha,
            "source": source,
            "replayOfPayloadSha256": payload_sha,
            "result": None,
        })
    return attempts


def classify_capture_lifecycle(attempts):
    """Classify capture attempts without promoting launcher or stale evidence."""
    outcomes = {}
    for attempt in attempts:
        attempt_id = _bounded_text(attempt.get("attemptId"), "attemptId")
        operation = attempt.get("operation")
        result = attempt.get("result")
        if not isinstance(result, dict):
            result = {}
        channel = "launcher" if operation == "launcher:capture" else "harness"
        states = ["requested"]
        terminal = "requested"
        if result.get("isError") is True:
            code = result.get("code")
            terminal = (
                "schema-rejected"
                if code in {"invalid-arguments", "schema-rejected"}
                else "execution-failed"
            )
            states.append(terminal)
        elif channel == "harness" and result.get("succeeded") is True:
            terminal = "succeeded"
            states.append("succeeded")
            if result.get("artifactCreated") is True:
                states.append("artifact-created")
            if result.get("inspected") is True:
                states.append("inspected")
            if result.get("stale") is True:
                states.append("stale")
            elif result.get("compared") is True:
                states.append("compared-current")
        elif channel == "launcher" and result.get("artifactCreated") is True:
            terminal = "artifact-created"
            states.append("artifact-created")
        outcomes[attempt_id] = {
            "channel": channel,
            "terminal": terminal,
            "states": states,
        }
    return outcomes


def classify_unproductive_loops(events, minimum_repeats=3):
    """Find consecutive equivalent errors with no declared progress."""
    if minimum_repeats < 2:
        raise TraceTaxonomyError("loop minimum must be at least two")
    loops = []
    start = 0
    while start < len(events):
        first = events[start]
        end = start + 1
        while end < len(events):
            current = events[end]
            if (current.get("operation") != first.get("operation")
                    or current.get("intentSha256") != first.get("intentSha256")
                    or current.get("errorClass") != first.get("errorClass")):
                break
            end += 1
        group = events[start:end]
        if (first.get("errorClass") is not None
                and len(group) >= minimum_repeats
                and all(not item.get("progress") for item in group)):
            loops.append({
                "schemaVersion": SCHEMA_VERSION,
                "ruleId": "equivalent-error-no-progress/v1",
                "evidenceClass": "observed",
                "eventRange": [group[0]["sequence"], group[-1]["sequence"]],
                "operation": first.get("operation"),
                "intentSha256": first.get("intentSha256"),
                "errorClass": first.get("errorClass"),
                "progressInputs": [item.get("progress", []) for item in group],
            })
        start = end
    return loops


def attribution(family, evidence_class, rule_id, event_ids, observation):
    if family not in FAMILIES:
        raise TraceTaxonomyError("unknown taxonomy family")
    if evidence_class not in EVIDENCE_CLASSES:
        raise TraceTaxonomyError("unknown evidence classification")
    if evidence_class == "hypothesis" and str(rule_id).startswith("cause/"):
        raise TraceTaxonomyError("hypothesis cannot be emitted as established cause")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "family": family,
        "evidenceClass": evidence_class,
        "ruleId": _bounded_text(rule_id, "ruleId"),
        "eventIds": list(event_ids),
        "observation": dict(observation),
    }


def semantic_attributions(evaluation, evidence_identity):
    """Collapse evaluator early exit into one source-established causal chain."""
    functional = evaluation.get("functional", {})
    assertions = functional.get("assertions", [])
    diagnostics = evaluation.get("diagnostics", [])
    missing_visible = any(
        isinstance(item, str)
        and "Missing bounded visibleControls observation" in item
        for item in diagnostics
    )
    passed_ids = [
        item.get("id")
        for item in assertions
        if isinstance(item, dict) and item.get("passed") is True
    ]
    missing_visible = missing_visible or (
        len(assertions) == 25
        and passed_ids == ["state.initial.values"]
        and sum(
            1 for item in assertions
            if isinstance(item, dict) and item.get("passed") is False
        ) == 24
    )
    if not missing_visible:
        aliases = [
            item.get("id")
            for item in assertions
            if isinstance(item, dict)
            and item.get("passed") is False
            and isinstance(item.get("evidence"), str)
            and "alias" in item["evidence"].lower()
        ]
        if not aliases:
            return []
        return [attribution(
            "semantic",
            "observed",
            "serialization-contract-alias-mismatch/v1",
            [],
            {
                "affectedAssertionIds": aliases,
                "evidenceIdentity": dict(evidence_identity),
                "behaviorFailure": "not-established",
            },
        )]
    affected = [
        item.get("id")
        for item in assertions
        if isinstance(item, dict)
        and item.get("passed") is False
        and isinstance(item.get("id"), str)
    ]
    return [attribution(
        "semantic",
        "source-established-cause",
        "evaluator-early-exit/missing-visible-controls/v1",
        [],
        {
            "expectedField": "checkpoints.initial.visibleControls",
            "observedPath": "candidate-state.visibleControls",
            "observedValue": "missing",
            "evaluatorDecision": "early-exit",
            "affectedAssertionIds": affected,
            "evidenceIdentity": dict(evidence_identity),
            "independentDefectCount": 1,
        },
    )]


def rendering_attributions(evaluation, evidence_identity):
    """Attach stable rendering categories to immutable comparison identities."""
    structural = evaluation.get("structural", [])
    structural_by_viewport = {}
    category_names = {
        "legibility": "typography",
        "affordance": "affordance",
        "hierarchy": "hierarchy-row-geometry",
        "clipping": "internal-clipping",
        "responsive": "responsive-composition",
        "scroll-stability": "repeat-stability",
    }
    for outcome in structural if isinstance(structural, list) else []:
        if not isinstance(outcome, dict):
            continue
        categories = []
        for signal in outcome.get("signals", []):
            if not isinstance(signal, dict):
                continue
            name = signal.get("name", signal.get("category"))
            failed = (
                signal.get("passed") is False
                or signal.get("status") == "FAIL"
            )
            if failed and isinstance(name, str):
                categories.append(category_names.get(name, name))
        evidence = outcome.get("evidence", {})
        viewport = (
            evidence.get("viewportId")
            if isinstance(evidence, dict)
            else outcome.get("viewportId")
        )
        structural_by_viewport.setdefault(
            viewport or outcome.get("viewportId"), []).extend(categories)

    results = []
    for visual in evaluation.get("visual", []):
        if not isinstance(visual, dict):
            continue
        metrics = visual.get("metrics", {})
        categories = list(structural_by_viewport.get(
            visual.get("viewportId"), []))
        clipping = metrics.get("clipping", {}) if isinstance(metrics, dict) else {}
        if isinstance(clipping, dict) and clipping.get("detected") is True:
            categories.append("frame-edge-clipping")
        results.append(attribution(
            "rendering",
            "observed",
            "evaluation-rendering-observation/v1",
            [],
            {
                "stateId": visual.get("referenceId"),
                "viewportId": visual.get("viewportId"),
                "captureSha256": list(visual.get("captureSha256", [])),
                "referenceSha256": visual.get("referenceSha256"),
                "evaluationIdentity": dict(evidence_identity),
                "categories": sorted(set(categories)),
                "metrics": dict(metrics) if isinstance(metrics, dict) else {},
                "causalInterpretation": "not-applicable",
            },
        ))
    return results


def public_trace(trace, evaluation, evaluation_sha256):
    """Return the digest-verifiable trace subset safe for blinded review."""
    if trace.get("schemaVersion") != SCHEMA_VERSION:
        raise TraceTaxonomyError("unsupported trace taxonomy")
    events = []
    public_event_ids = {}
    allowed_event_fields = (
        "sequence", "kind", "operation",
        "requestId", "payloadSha256", "evidenceClass", "errorClass",
        "attemptId",
    )
    for raw in trace.get("events", []):
        if not isinstance(raw, dict):
            raise TraceTaxonomyError("trace event must be an object")
        parents = raw.get("parentEventIds", [])
        if any(parent not in public_event_ids for parent in parents):
            raise TraceTaxonomyError("public trace parent is not ordered")
        public = {
            "schemaVersion": "agentic-palisade/blind-trace-event-v1",
            "sourceEventSha256": raw.get("eventId"),
            "parentEventIds": [public_event_ids[parent] for parent in parents],
            **{
            key: raw[key] for key in allowed_event_fields if key in raw
            },
        }
        public = seal_event(public)
        public_event_ids[raw.get("eventId")] = public["eventId"]
        events.append(public)
    attempts = []
    for raw in trace.get("captureAttempts", []):
        if not isinstance(raw, dict) or not isinstance(raw.get("source"), dict):
            raise TraceTaxonomyError("capture attempt source is missing")
        attempt = {
            key: raw.get(key)
            for key in (
                "attemptId", "requestId", "operation", "payloadSha256",
                "replayOfPayloadSha256", "result",
            )
        }
        attempt["source"] = {
            key: raw["source"].get(key)
            for key in ("kind", "sha256")
        }
        attempts.append(attempt)
    raw_attributions = trace.get("attributions", {})
    attributions = {}
    for family in FAMILIES:
        attributions[family] = []
        for raw in raw_attributions.get(family, []):
            public = dict(raw)
            public["eventIds"] = [
                public_event_ids[event_id]
                for event_id in raw.get("eventIds", [])
                if event_id in public_event_ids
            ]
            attributions[family].append(public)
    evidence_identity = {"evaluationSha256": evaluation_sha256}
    attributions["semantic"] = semantic_attributions(
        evaluation, evidence_identity)
    attributions["rendering"] = rendering_attributions(
        evaluation, evidence_identity)
    evidence_gaps = []
    for raw in trace.get("evidenceGaps", []):
        if not isinstance(raw, dict):
            raise TraceTaxonomyError("trace evidence gap must be an object")
        reference = _bounded_text(raw.get("reference"), "evidence gap reference")
        tool_call_id = _bounded_text(
            raw.get("toolCallId"), "evidence gap toolCallId")
        evidence_gaps.append({
            "code": _bounded_text(raw.get("code"), "evidence gap code"),
            "message": _bounded_text(raw.get("message"), "evidence gap message"),
            "referenceSha256": sha256_bytes(reference.encode("utf-8")),
            "toolCallIdSha256": sha256_bytes(tool_call_id.encode("utf-8")),
        })
    return {
        "schemaVersion": SCHEMA_VERSION,
        "availability": trace.get("availability", "unavailable"),
        "parserVersion": "normalized-trace-parser/v2",
        "sequenceBasis": trace.get("sequenceBasis", "unavailable"),
        "knownExclusions": [
            "hidden reasoning",
            "undeclared external payloads",
            "causal inference from association",
        ],
        "evidenceGaps": evidence_gaps,
        "events": events,
        "captureAttempts": attempts,
        "captureLifecycle": dict(trace.get("captureLifecycle", {})),
        "attributions": attributions,
    }


def summarize_protocol_records(records):
    """Count visible protocol invocations and responses without causal inference."""
    if not isinstance(records, list) or len(records) > 65_536:
        raise TraceTaxonomyError("protocol records must be a bounded array")
    requests = set()
    succeeded = 0
    errors = {}
    for record in records:
        if not isinstance(record, dict):
            raise TraceTaxonomyError("protocol record must be an object")
        request_id = _bounded_text(record.get("requestId"), "requestId")
        kind = record.get("kind")
        if kind == "invocation":
            if request_id in requests:
                raise TraceTaxonomyError("duplicate protocol invocation")
            requests.add(request_id)
        elif kind == "response":
            if request_id not in requests:
                raise TraceTaxonomyError("protocol response has no invocation")
            if record.get("status") == "succeeded":
                succeeded += 1
            elif isinstance(record.get("errorClass"), str):
                error_class = _bounded_text(
                    record["errorClass"], "errorClass")
                errors[error_class] = errors.get(error_class, 0) + 1
            else:
                raise TraceTaxonomyError("protocol response has no outcome")
        else:
            raise TraceTaxonomyError("unknown protocol record kind")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "ruleId": "visible-protocol-record-count/v1",
        "evidenceClass": "observed",
        "invocations": len(requests),
        "successfulResponses": succeeded,
        "errorResponses": dict(sorted(errors.items())),
        "causalInterpretation": "not-established",
    }
