"""Bounded normalization and validation for verified Codex user-input requests."""

from __future__ import annotations

import copy
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from approvals import (
    MCP_ELICITATION_METHOD,
    USER_INPUT_METHOD,
    ApprovalError,
    approval_key,
    bounded_text,
)
INPUT_METHODS = {USER_INPUT_METHOD, MCP_ELICITATION_METHOD}

MAX_FIELDS = 8
MAX_OPTIONS = 50
MAX_TEXT_LENGTH = 4_096
LONG_TEXT_THRESHOLD = 512
MAX_LABEL = 500
MAX_DESCRIPTION = 4_096


def _integer(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _text(value: Any, limit: int = MAX_DESCRIPTION) -> str | None:
    return bounded_text(value, limit)


def _option(value: str, label: str | None = None, description: str | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {"value": value, "label": label or value}
    if description:
        result["description"] = description
    return result


def _unique_strings(
    values: Any, *, limit: int = MAX_OPTIONS, allow_empty: bool = False
) -> list[str] | None:
    if not isinstance(values, list) or (not values and not allow_empty) or len(values) > limit:
        return None
    result: list[str] = []
    for value in values:
        if not isinstance(value, str) or not value or len(value) > MAX_LABEL or value in result:
            return None
        result.append(value)
    return result


def _selection_options(schema: dict[str, Any], multiple: bool) -> list[dict[str, Any]] | None:
    source = schema.get("items") if multiple else schema
    if not isinstance(source, dict):
        return None
    if "enum" in source:
        values = _unique_strings(source.get("enum"))
        if values is None:
            return None
        names = source.get("enumNames") if not multiple else None
        if names is not None:
            names = _unique_strings(names)
            if names is None or len(names) != len(values):
                return None
        return [_option(value, names[index] if names else value) for index, value in enumerate(values)]
    key = "anyOf" if multiple else "oneOf"
    raw_options = source.get(key)
    if not isinstance(raw_options, list) or not raw_options or len(raw_options) > MAX_OPTIONS:
        return None
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for raw in raw_options:
        if not isinstance(raw, dict) or set(raw) != {"const", "title"}:
            return None
        value, label = raw.get("const"), raw.get("title")
        if (
            not isinstance(value, str)
            or not value
            or len(value) > MAX_LABEL
            or value in seen
            or not isinstance(label, str)
            or not label
            or len(label) > MAX_LABEL
        ):
            return None
        seen.add(value)
        result.append(_option(value, label))
    return result


def _normalize_tool(params: dict[str, Any]) -> tuple[list[dict[str, Any]], str | None]:
    questions = params.get("questions")
    if not isinstance(questions, list) or not 1 <= len(questions) <= 3:
        return [], "Codex request_user_input must contain 1 to 3 questions."
    fields: list[dict[str, Any]] = []
    ids: set[str] = set()
    for raw in questions:
        if not isinstance(raw, dict):
            return [], "A Codex user-input question is malformed."
        field_id = _text(raw.get("id"), 200)
        label = _text(raw.get("header"), MAX_LABEL)
        question = _text(raw.get("question"))
        if not field_id or field_id in ids or not label or not question:
            return [], "A Codex user-input question is missing a unique id, header, or question."
        ids.add(field_id)
        options = raw.get("options")
        field: dict[str, Any] = {
            "id": field_id,
            "label": label,
            "description": question,
            "required": True,
            "secret": raw.get("isSecret") is True,
        }
        if options is None:
            field.update({"type": "shortText", "minLength": 1, "maxLength": MAX_TEXT_LENGTH})
        elif isinstance(options, list) and options and len(options) <= MAX_OPTIONS:
            projected = []
            seen: set[str] = set()
            for option in options:
                if not isinstance(option, dict):
                    return [], "A Codex user-input option is malformed."
                value = _text(option.get("label"), MAX_LABEL)
                description = _text(option.get("description"))
                if not value or value in seen or description is None:
                    return [], "Codex user-input options require unique labels and descriptions."
                seen.add(value)
                projected.append(_option(value, value, description))
            field.update({"type": "singleChoice", "options": projected})
            if raw.get("isOther") is True:
                field["allowOther"] = True
                field["maxLength"] = MAX_TEXT_LENGTH
        else:
            return [], "Codex user-input choices require a non-empty bounded option list."
        fields.append(field)
    return fields, None


def _normalize_mcp_field(
    field_id: str, schema: Any, required: bool
) -> tuple[dict[str, Any] | None, str | None]:
    if not isinstance(schema, dict):
        return None, f"Field {field_id} is not a supported primitive schema."
    title = _text(schema.get("title"), MAX_LABEL) or field_id
    description = _text(schema.get("description"))
    field: dict[str, Any] = {
        "id": field_id,
        "label": title,
        "description": description,
        "required": required,
    }
    kind = schema.get("type")
    allowed_keys = {
        "string": {"type", "title", "description", "default", "minLength", "maxLength", "format", "enum", "enumNames", "oneOf"},
        "array": {"type", "title", "description", "default", "minItems", "maxItems", "items"},
        "boolean": {"type", "title", "description", "default"},
    }.get(kind)
    if allowed_keys is None or not set(schema) <= allowed_keys:
        return None, f"Field {field_id} uses unsupported schema keywords."
    if kind == "string" and any(key in schema for key in ("enum", "oneOf")):
        options = _selection_options(schema, False)
        if options is None:
            return None, f"Field {field_id} has malformed single-choice options."
        field.update({"type": "singleChoice", "options": options})
        default = schema.get("default")
        if default is not None:
            if not isinstance(default, str) or default not in {item["value"] for item in options}:
                return None, f"Field {field_id} has an invalid default choice."
            field["default"] = default
        return field, None
    if kind == "array":
        options = _selection_options(schema, True)
        if options is None:
            return None, f"Field {field_id} is not a supported string-array choice."
        minimum = _integer(schema.get("minItems"))
        maximum = _integer(schema.get("maxItems"))
        minimum = 0 if minimum is None else minimum
        maximum = len(options) if maximum is None else maximum
        if minimum < 0 or maximum < minimum or maximum > len(options):
            return None, f"Field {field_id} has invalid selection bounds."
        field.update({
            "type": "multipleChoice",
            "options": options,
            "minSelections": minimum,
            "maxSelections": maximum,
        })
        default = schema.get("default")
        if default is not None:
            values = _unique_strings(default, allow_empty=True)
            available = {item["value"] for item in options}
            if values is None or not set(values) <= available or not minimum <= len(values) <= maximum:
                return None, f"Field {field_id} has invalid default selections."
            field["default"] = values
        return field, None
    if kind == "string":
        if schema.get("format") is not None:
            return None, f"Field {field_id} uses an unsupported formatted string."
        minimum = _integer(schema.get("minLength"))
        explicit_maximum = _integer(schema.get("maxLength"))
        maximum = explicit_maximum
        minimum = 0 if minimum is None else minimum
        maximum = MAX_TEXT_LENGTH if maximum is None else maximum
        if minimum < 0 or maximum < minimum or maximum > MAX_TEXT_LENGTH:
            return None, f"Field {field_id} has unsupported length bounds."
        field.update({
            "type": "longText" if explicit_maximum is not None and maximum > LONG_TEXT_THRESHOLD else "shortText",
            "minLength": minimum,
            "maxLength": maximum,
        })
        default = schema.get("default")
        if default is not None:
            if not isinstance(default, str) or not minimum <= len(default) <= maximum:
                return None, f"Field {field_id} has an invalid default value."
            field["default"] = default
        return field, None
    if kind == "boolean":
        field["type"] = "boolean"
        default = schema.get("default")
        if default is not None:
            if not isinstance(default, bool):
                return None, f"Field {field_id} has an invalid boolean default."
            field["default"] = default
        return field, None
    return None, f"Field {field_id} uses unsupported type {kind!r}."


def _normalize_mcp(params: dict[str, Any]) -> tuple[list[dict[str, Any]], str | None]:
    mode = params.get("mode")
    if mode != "form":
        return [], f"MCP elicitation mode {mode!r} is not supported by Foreman."
    schema = params.get("requestedSchema")
    if not isinstance(schema, dict) or schema.get("type") != "object":
        return [], "MCP elicitation requires a flat object schema."
    if not set(schema) <= {"$schema", "type", "properties", "required"}:
        return [], "MCP elicitation uses unsupported object-schema keywords."
    properties = schema.get("properties")
    if not isinstance(properties, dict) or len(properties) > MAX_FIELDS:
        return [], f"MCP elicitation must contain at most {MAX_FIELDS} fields."
    required_raw = schema.get("required", [])
    if not isinstance(required_raw, list) or any(not isinstance(value, str) for value in required_raw):
        return [], "MCP elicitation has a malformed required-field list."
    required = set(required_raw)
    if len(required) != len(required_raw) or not required <= set(properties):
        return [], "MCP elicitation required fields must be unique schema properties."
    fields: list[dict[str, Any]] = []
    for field_id, field_schema in properties.items():
        if not isinstance(field_id, str) or not field_id or len(field_id) > 200:
            return [], "MCP elicitation field names must be non-empty bounded strings."
        field, reason = _normalize_mcp_field(field_id, field_schema, field_id in required)
        if field is None:
            return [], reason
        fields.append(field)
    if len(fields) == 1 and fields[0]["type"] == "boolean" and fields[0]["required"]:
        fields[0]["type"] = "confirmation"
    return fields, None


def bounded_input_params(method: str, value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    result: dict[str, Any] = {}
    for key in ("threadId", "turnId", "itemId", "serverName"):
        text = _text(value.get(key), 500)
        if text:
            result[key] = text
    if method == USER_INPUT_METHOD:
        # Normalization copies only the verified fields and bounds all strings.
        questions = value.get("questions")
        result["questions"] = copy.deepcopy(questions) if isinstance(questions, list) else questions
        timeout = _integer(value.get("autoResolutionMs"))
        result["autoResolutionMs"] = timeout if timeout is not None and timeout >= 0 else None
    else:
        result["mode"] = value.get("mode")
        result["message"] = _text(value.get("message"))
        result["requestedSchema"] = copy.deepcopy(value.get("requestedSchema"))
    return result


def _validate_text(field: dict[str, Any], value: Any) -> str:
    if not isinstance(value, str):
        raise ApprovalError(f"{field['label']} must be text")
    minimum, maximum = field.get("minLength", 0), field.get("maxLength", MAX_TEXT_LENGTH)
    if not minimum <= len(value) <= maximum:
        raise ApprovalError(f"{field['label']} must be between {minimum} and {maximum} characters")
    return value


def _validate_field(field: dict[str, Any], value: Any) -> Any:
    kind = field["type"]
    if kind in ("shortText", "longText"):
        return _validate_text(field, value)
    if kind == "singleChoice":
        available = {item["value"] for item in field["options"]}
        if isinstance(value, str) and value in available:
            return value
        if field.get("allowOther") and isinstance(value, str) and 1 <= len(value) <= field.get("maxLength", MAX_TEXT_LENGTH):
            return value
        raise ApprovalError(f"{field['label']} must be an available choice")
    if kind == "multipleChoice":
        if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
            raise ApprovalError(f"{field['label']} must be a list of choices")
        if len(set(value)) != len(value):
            raise ApprovalError(f"{field['label']} cannot contain duplicate choices")
        available = {item["value"] for item in field["options"]}
        if not set(value) <= available:
            raise ApprovalError(f"{field['label']} contains an unavailable choice")
        if not field["minSelections"] <= len(value) <= field["maxSelections"]:
            raise ApprovalError(
                f"{field['label']} requires {field['minSelections']} to {field['maxSelections']} choices"
            )
        return value
    if kind in ("boolean", "confirmation"):
        if not isinstance(value, bool):
            raise ApprovalError(f"{field['label']} must be yes or no")
        return value
    raise ApprovalError(f"{field['label']} is unsupported")


@dataclass
class PendingInput:
    upstream_id: Any
    method: str
    params: dict[str, Any]
    connection: Any = field(default=None, repr=False)
    id: str = field(default_factory=lambda: f"inp_{uuid.uuid4().hex}")
    created_at: float = field(default_factory=time.time)
    status: str = "pending"
    resolution: str | None = None
    lock: Any = field(default=None, repr=False)
    fields: list[dict[str, Any]] = field(default_factory=list)
    unsupported_message: str | None = None

    def __post_init__(self) -> None:
        import asyncio

        self.lock = asyncio.Lock()
        self.fields, self.unsupported_message = (
            _normalize_tool(self.params)
            if self.method == USER_INPUT_METHOD
            else _normalize_mcp(self.params)
        )
        # The bounded normalized fields are sufficient after construction.
        # Never retain a raw request schema or question array in pending state.
        self.params.pop("questions", None)
        self.params.pop("requestedSchema", None)

    @property
    def thread_id(self) -> str:
        return self.params.get("threadId", "")

    @property
    def turn_id(self) -> str:
        return self.params.get("turnId", "")

    @property
    def item_id(self) -> str:
        return self.params.get("itemId", "")

    @property
    def supported(self) -> bool:
        return self.unsupported_message is None

    def projection(self) -> dict[str, Any]:
        is_mcp = self.method == MCP_ELICITATION_METHOD
        is_confirmation = is_mcp and self.supported and not self.fields
        return {
            "id": self.id,
            "sessionId": self.thread_id,
            "turnId": self.turn_id or None,
            "itemId": self.item_id or None,
            "source": "mcp" if is_mcp else "codex",
            "title": (
                "Input requested"
                if not is_mcp
                else "Confirmation requested"
                if is_confirmation
                else "MCP input requested"
            ),
            "message": self.params.get("message") if is_mcp else None,
            "serverName": self.params.get("serverName") if is_mcp else None,
            "fields": copy.deepcopy(self.fields),
            "supported": self.supported,
            "unsupportedMessage": self.unsupported_message,
            "canDecline": is_mcp,
            "canCancel": is_mcp,
            "autoResolutionMs": self.params.get("autoResolutionMs") if not is_mcp else None,
            "createdAt": int(self.created_at),
            "status": self.status,
            "resolution": self.resolution,
        }

    def response_result(self, response: Any) -> tuple[dict[str, Any], str]:
        if not isinstance(response, dict):
            raise ApprovalError("input response must be an object")
        action = response.get("action", "accept")
        if action in ("decline", "cancel"):
            if self.method != MCP_ELICITATION_METHOD:
                raise ApprovalError("this request cannot be declined or cancelled")
            return {"action": action, "content": None}, action
        if action != "accept":
            raise ApprovalError("input action is unavailable")
        if not self.supported:
            raise ApprovalError("this input schema is not supported in Foreman")
        values = response.get("values")
        if not isinstance(values, dict):
            raise ApprovalError("input values must be an object")
        available = {field["id"] for field in self.fields}
        if not set(values) <= available:
            raise ApprovalError("input values contain an unknown field")
        validated: dict[str, Any] = {}
        for field in self.fields:
            field_id = field["id"]
            if field_id not in values:
                if field["required"]:
                    raise ApprovalError(f"{field['label']} is required")
                continue
            value = values[field_id]
            if value is None and not field["required"]:
                continue
            validated[field_id] = _validate_field(field, value)
        if self.method == USER_INPUT_METHOD:
            return {
                "answers": {
                    field_id: {"answers": value if isinstance(value, list) else [value]}
                    for field_id, value in validated.items()
                }
            }, "accepted"
        return {"action": "accept", "content": validated}, "accepted"


__all__ = [
    "INPUT_METHODS",
    "MCP_ELICITATION_METHOD",
    "PendingInput",
    "USER_INPUT_METHOD",
    "approval_key",
    "bounded_input_params",
]
