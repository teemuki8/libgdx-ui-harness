#!/usr/bin/env python3
"""Validate the frozen Agentic Palisade public corpus without external packages."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import struct
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "corpus" / "spec.json"
SCHEMA_PATH = ROOT / "corpus" / "schema" / "spec.schema.json"
PROTOCOL_PATH = ROOT / "PROTOCOL.md"
REFERENCE_DIR = ROOT / "corpus" / "reference"

EXPECTED_CONTROL_IDS = [
    "map",
    "playerRealm",
    "majorRivalCount",
    "pettyRealmDensity",
    "startingResources",
    "victoryCondition",
    "rivalTargetCount",
    "aiDifficulty",
    "simulationSpeed",
    "richHarvest",
    "scarceGold",
    "costlyCavalry",
    "cavalryRush",
    "roadBoom",
    "seed",
    "copySeed",
    "randomSeed",
    "cancel",
    "startBattle",
]
EXPECTED_LABELS = [
    "Map",
    "Player realm",
    "Major rival count",
    "Petty realm density",
    "Starting resources",
    "Victory condition",
    "Rival target count",
    "AI difficulty",
    "Simulation speed",
    "High income",
    "Low income",
    "Higher cavalry cost",
    "Faster cavalry",
    "Faster road construction",
    "Seed",
    "COPY SEED",
    "RANDOM SEED",
    "CANCEL",
    "START BATTLE",
]
EXPECTED_DEFAULTS = [
    "northern-realms-860",
    "vestfold",
    3,
    "standard",
    "standard",
    "conquest",
    1,
    "standard",
    "1",
    False,
    False,
    False,
    False,
    False,
    "generatedUint32",
    None,
    None,
    None,
    None,
]
EXPECTED_OPTIONS = {
    "map": [
        ("northern-realms-860", "The Northern Realms"),
        ("great-army-865", "The Great Army"),
        ("seine-885", "The Seine"),
        ("ji-north-china-plain", "The Yellow Sky"),
        ("yingchuan-runan-corridors", "Fires of Yingchuan"),
        ("nanyang-wan-basin", "Wan Under Siege"),
        ("eastern-japan-1180", "The Eastern Muster"),
        ("ichi-no-tani", "Ichi-no-Tani"),
        ("yashima-1185", "Yashima"),
        ("seto-inland-sea", "The Western Strait"),
        ("vega-real-1495", "The Vega Real"),
        ("lucayan-crossings-1509", "Sails over Lucaya"),
        ("windward-passage-1511", "The Windward Passage"),
        ("lake-under-tribute-1428", "The Lake Under Tribute"),
        ("return-to-texcoco-1428", "Return to Tetzcoco"),
        ("coyoacan-holds-1431", "Coyohuacan Holds"),
    ],
    "playerRealm": [
        ("vestfold", "Vestfold"),
        ("danish-realm", "Danish Realm"),
        ("swealand", "Swealand"),
        ("halogaland", "Hålogaland"),
    ],
    "pettyRealmDensity": [
        ("sparse", "Low"),
        ("standard", "Medium"),
        ("dense", "High"),
    ],
    "startingResources": [
        ("scarce", "Low"),
        ("standard", "Standard"),
        ("abundant", "High"),
    ],
    "victoryCondition": [
        ("conquest", "Total conquest"),
        ("rival-target", "Rival target"),
        ("territorial-dominance", "Territorial control"),
    ],
    "aiDifficulty": [
        ("standard", "Standard"),
        ("hardened", "Hard"),
        ("relentless", "Very hard"),
    ],
    "simulationSpeed": [
        ("1", "Normal"),
        ("2", "Fast"),
    ],
}
EXPECTED_REFERENCES = {
    "initial-1920x1080": ("initial-1920x1080.png", 1920, 1080, "initial", "desktop-1920x1080"),
    "bottom-1920x1080": ("bottom-1920x1080.png", 1920, 1080, "bottom", "desktop-1920x1080"),
    "initial-1280x720": ("initial-1280x720.png", 1280, 720, "initial", "desktop-1280x720"),
}
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class ValidationError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def load_json(path: Path) -> Any:
    require(path.is_file(), f"missing required artifact: {path.relative_to(ROOT)}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValidationError(f"invalid JSON in {path.relative_to(ROOT)}: {error}") from error


def exact_keys(value: Any, expected: set[str], context: str) -> None:
    require(isinstance(value, dict), f"{context} must be an object")
    actual = set(value)
    require(actual == expected, f"{context} keys must be exactly {sorted(expected)}; got {sorted(actual)}")

def matches_type(value: Any, expected: str) -> bool:
    return {
        "null": value is None,
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
    }.get(expected, False)


def resolve_reference(reference: str, root_schema: dict[str, Any]) -> Any:
    require(reference.startswith("#/"), f"only local schema references are allowed: {reference}")
    resolved: Any = root_schema
    for component in reference[2:].split("/"):
        component = component.replace("~1", "/").replace("~0", "~")
        require(isinstance(resolved, dict) and component in resolved, f"unresolved schema reference: {reference}")
        resolved = resolved[component]
    return resolved


def validate_instance(value: Any, schema: Any, root_schema: dict[str, Any], context: str) -> None:
    require(isinstance(schema, dict), f"{context} schema must be an object")
    if "$ref" in schema:
        validate_instance(value, resolve_reference(schema["$ref"], root_schema), root_schema, context)
        return
    if "oneOf" in schema:
        matches = 0
        for alternative in schema["oneOf"]:
            try:
                validate_instance(value, alternative, root_schema, context)
                matches += 1
            except ValidationError:
                pass
        require(matches == 1, f"{context} must match exactly one schema alternative")
        return

    expected_types = schema.get("type")
    if expected_types is not None:
        if isinstance(expected_types, str):
            expected_types = [expected_types]
        require(any(matches_type(value, expected) for expected in expected_types), f"{context} has the wrong JSON type")
    if "const" in schema:
        require(value == schema["const"], f"{context} must equal {schema['const']!r}")
    if "enum" in schema:
        require(value in schema["enum"], f"{context} is not an approved enum value")

    if isinstance(value, dict):
        required = schema.get("required", [])
        missing = [key for key in required if key not in value]
        require(not missing, f"{context} is missing required properties {missing}")
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False:
            extras = sorted(set(value) - set(properties))
            require(not extras, f"{context} contains additional properties {extras}")
        for key, child in value.items():
            if key in properties:
                validate_instance(child, properties[key], root_schema, f"{context}.{key}")
    elif isinstance(value, list):
        require(len(value) >= schema.get("minItems", 0), f"{context} has too few items")
        if "maxItems" in schema:
            require(len(value) <= schema["maxItems"], f"{context} has too many items")
        if schema.get("uniqueItems"):
            serialized = [json.dumps(item, sort_keys=True) for item in value]
            require(len(serialized) == len(set(serialized)), f"{context} items must be unique")
        if "items" in schema:
            for index, item in enumerate(value):
                validate_instance(item, schema["items"], root_schema, f"{context}[{index}]")
    elif isinstance(value, str):
        require(len(value) >= schema.get("minLength", 0), f"{context} is too short")
        if "pattern" in schema:
            require(re.search(schema["pattern"], value) is not None, f"{context} does not match its pattern")
    elif isinstance(value, int) and not isinstance(value, bool):
        if "minimum" in schema:
            require(value >= schema["minimum"], f"{context} is below its minimum")
        if "maximum" in schema:
            require(value <= schema["maximum"], f"{context} is above its maximum")


def validate_schema(schema: Any) -> None:
    exact_keys(
        schema,
        {"$schema", "$id", "title", "type", "additionalProperties", "required", "properties", "$defs"},
        "schema",
    )
    require(schema["$schema"] == "https://json-schema.org/draft/2020-12/schema", "schema draft must be 2020-12")
    require(schema["$id"] == "agentic-palisade/v1/spec.schema.json", "unexpected schema $id")
    require(schema["type"] == "object", "schema root must describe an object")
    require(schema["additionalProperties"] is False, "schema root must reject additional properties")
    required = ["schemaVersion", "title", "controls", "states", "transitions", "viewports", "references"]
    require(schema["required"] == required, "schema root required list/order is not strict")
    require(list(schema["properties"]) == required, "schema root properties/order must match the public document")
    require(schema["properties"]["schemaVersion"] == {"const": "agentic-palisade/v1"}, "schema version must be constant")
    for definition_name, definition in schema["$defs"].items():
        if definition.get("type") == "object":
            require(definition.get("additionalProperties") is False, f"schema definition {definition_name} is not strict")
            require(definition.get("required") == list(definition.get("properties", {})), f"schema definition {definition_name} must require every property in order")


def validate_controls(controls: Any) -> None:
    require(isinstance(controls, list), "controls must be an array")
    ids = [control.get("id") for control in controls if isinstance(control, dict)]
    require(len(ids) == len(controls), "every control must be an object with an id")
    require(len(ids) == 19, f"expected 19 controls, got {len(ids)}")
    require(ids == EXPECTED_CONTROL_IDS, f"unexpected controls/order: {ids}")
    require([control.get("label") for control in controls] == EXPECTED_LABELS, "unexpected control labels/order")
    require([control.get("default") for control in controls] == EXPECTED_DEFAULTS, "unexpected control defaults/order")
    controls_by_id = {control["id"]: control for control in controls}
    option_control_ids = {control["id"] for control in controls if control["options"]}
    require(option_control_ids == set(EXPECTED_OPTIONS), f"unexpected option-bearing controls: {sorted(option_control_ids)}")
    for control_id, expected_options in EXPECTED_OPTIONS.items():
        actual_options = [(option["value"], option["label"]) for option in controls_by_id[control_id]["options"]]
        require(actual_options == expected_options, f"unexpected options/order for {control_id}: {actual_options}")
    require(all(control.get("focusOrder") == index + 1 for index, control in enumerate(controls)), "focusOrder must be contiguous and match control order")
    conditional = controls[EXPECTED_CONTROL_IDS.index("rivalTargetCount")]["visibleWhen"]
    require(conditional == {"controlId": "victoryCondition", "equals": "rival-target"}, "unexpected rival-target visibility rule")
    require(all(control["visibleWhen"] is None for control in controls if control["id"] != "rivalTargetCount"), "only rivalTargetCount may be conditional")
    seed_validation = controls[EXPECTED_CONTROL_IDS.index("seed")]["validation"]
    require(seed_validation == {"minimum": 0, "maximum": 4294967295, "step": 1, "format": "uint32-decimal"}, "unexpected seed validation")


def validate_spec(spec: Any) -> None:
    required = ["schemaVersion", "title", "controls", "states", "transitions", "viewports", "references"]
    exact_keys(spec, set(required), "spec")
    require(list(spec) == required, "spec keys must use the approved public order")
    require(spec["schemaVersion"] == "agentic-palisade/v1", "unexpected schemaVersion")
    require(isinstance(spec["title"], str) and spec["title"], "title must be non-empty")
    validate_controls(spec["controls"])
    for key in ("states", "transitions", "viewports", "references"):
        require(isinstance(spec[key], list) and spec[key], f"{key} must be a non-empty ordered array")

    state_ids = [state.get("id") for state in spec["states"]]
    require(state_ids == ["initial", "bottom", "confirmation"], f"unexpected states/order: {state_ids}")
    transition_ids = [transition.get("id") for transition in spec["transitions"]]
    require(transition_ids == ["scrollToBottom", "copySeed", "randomSeed", "cancel", "escape", "startBattle"], f"unexpected transitions/order: {transition_ids}")
    viewports = [(viewport.get("width"), viewport.get("height")) for viewport in spec["viewports"]]
    require(viewports == [(1920, 1080), (1280, 720)], f"unexpected viewports/order: {viewports}")
    transitions = {transition["id"]: transition for transition in spec["transitions"]}
    require(transitions["randomSeed"]["fixture"] == {"randomUint32": 305419896, "expectedSeed": 305419896, "expectedClipboardText": None}, "unexpected deterministic random-seed fixture")
    require(transitions["copySeed"]["fixture"]["expectedClipboardText"] == "305419896", "unexpected copy-seed fixture")
    require(transitions["cancel"]["to"] == "dismissed" and transitions["escape"]["to"] == "dismissed", "cancel and Escape must dismiss")
    confirmation = spec["states"][2]
    require(confirmation["payload"] == confirmation["values"], "confirmation payload must match accepted values")
    require(transitions["startBattle"]["to"] == "confirmation", "START BATTLE must produce confirmation state")


def png_dimensions(data: bytes, context: str) -> tuple[int, int]:
    require(data.startswith(PNG_SIGNATURE), f"{context} has an invalid PNG signature")
    require(len(data) >= 24 and data[12:16] == b"IHDR", f"{context} has no valid IHDR")
    return struct.unpack(">II", data[16:24])


def validate_references(references: Any) -> None:
    require([reference.get("id") for reference in references] == list(EXPECTED_REFERENCES), "unexpected references/order")
    for reference in references:
        reference_id = reference["id"]
        filename, expected_width, expected_height, expected_state, expected_viewport = EXPECTED_REFERENCES[reference_id]
        require(reference.get("stateId") == expected_state, f"unexpected state binding for {reference_id}")
        require(reference.get("viewportId") == expected_viewport, f"unexpected viewport binding for {reference_id}")
        require(reference.get("file") == f"reference/{filename}", f"unexpected file for {reference_id}")
        require(reference.get("width") == expected_width and reference.get("height") == expected_height, f"recorded dimensions mismatch for {reference_id}")
        path = REFERENCE_DIR / filename
        require(path.is_file(), f"missing required artifact: {path.relative_to(ROOT)}")
        data = path.read_bytes()
        require(png_dimensions(data, reference_id) == (expected_width, expected_height), f"PNG dimensions mismatch for {reference_id}")
        require(reference.get("bytes") == len(data), f"byte length mismatch for {reference_id}")
        require(reference.get("sha256") == hashlib.sha256(data).hexdigest(), f"SHA-256 mismatch for {reference_id}")

def require_rejected(value: Any, validator: Any, label: str) -> None:
    try:
        validator(value)
    except ValidationError:
        return
    raise ValidationError(f"validator accepted negative mutation: {label}")


def validate_negative_mutations(spec: Any) -> None:
    map_index = EXPECTED_CONTROL_IDS.index("map")
    mutations = []

    removed = copy.deepcopy(spec)
    removed["controls"][map_index]["options"].pop()
    mutations.append(("removed option", removed))

    reordered = copy.deepcopy(spec)
    reordered["controls"][map_index]["options"][0:2] = reversed(reordered["controls"][map_index]["options"][0:2])
    mutations.append(("reordered options", reordered))

    relabeled = copy.deepcopy(spec)
    relabeled["controls"][map_index]["options"][0]["label"] = "Changed label"
    mutations.append(("relabeled option", relabeled))

    changed_value = copy.deepcopy(spec)
    changed_value["controls"][map_index]["options"][0]["value"] = "changed-value"
    mutations.append(("changed option value", changed_value))

    for label, mutated in mutations:
        require_rejected(mutated, validate_spec, label)

    wrong_state = copy.deepcopy(spec["references"])
    wrong_state[0]["stateId"] = "bottom"
    require_rejected(wrong_state, validate_references, "changed reference state")

    wrong_viewport = copy.deepcopy(spec["references"])
    wrong_viewport[0]["viewportId"] = "desktop-1280x720"
    require_rejected(wrong_viewport, validate_references, "changed reference viewport")


def validate_blinding() -> None:
    require(PROTOCOL_PATH.is_file(), "missing required artifact: PROTOCOL.md")
    forbidden_suffixes = {"." + "html", "." + "css", "." + "ts", "." + "tsx"}
    absolute_markers = ("/" + "home/", "\\\\" + "Users" + "\\\\")
    source_fragments = ("<" + "script", "<" + "style", "source" + "MappingURL")
    for path in ROOT.rglob("*"):
        relative = path.relative_to(ROOT)
        if (not path.is_file()
                or any(part in {".gradle", "build", "__pycache__"}
                       for part in relative.parts)):
            continue
        require(path.suffix.lower() not in forbidden_suffixes, f"forbidden reference source file: {path.relative_to(ROOT)}")
        require(not ("hidden" in path.name.lower() and "test" in path.name.lower()), f"non-public test artifact is forbidden: {path.relative_to(ROOT)}")
        if path.suffix == ".png":
            continue
        text = path.read_text(encoding="utf-8")
        for token in (*absolute_markers, *source_fragments):
            require(token.lower() not in text.lower(), f"forbidden source/path detail in {path.relative_to(ROOT)}")


def main() -> int:
    try:
        schema = load_json(SCHEMA_PATH)
        spec = load_json(SPEC_PATH)
        validate_schema(schema)
        validate_instance(spec, schema, schema, "spec")
        validate_spec(spec)
        validate_negative_mutations(spec)
        validate_references(spec["references"])
        validate_blinding()
    except ValidationError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print("PASS: agentic-palisade/v1 corpus is internally consistent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
