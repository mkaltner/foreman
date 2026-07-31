"""The minimal provider-aware session identity used at service boundaries."""

from __future__ import annotations

from dataclasses import dataclass


PROVIDERS = frozenset(("codex", "claude-code"))


@dataclass(frozen=True)
class SessionIdentity:
    provider: str
    host_id: str
    session_id: str

    def __post_init__(self) -> None:
        if self.provider not in PROVIDERS:
            raise ValueError("unsupported session provider")
        if not self.host_id or not self.session_id:
            raise ValueError("host_id and session_id are required")

    def projection(self) -> dict[str, str]:
        return {
            "provider": self.provider,
            "hostId": self.host_id,
            "sessionId": self.session_id,
        }
