from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field, model_validator


class AgentChatRequest(BaseModel):
    thread_id: str = Field(min_length=1, max_length=120)
    username: str = Field(min_length=1, max_length=64)
    user_id: int = Field(gt=0)
    role: str = Field(min_length=1, max_length=20)
    kb_id: int = Field(gt=0)
    message: str | None = Field(default=None, max_length=4000)
    decision: Literal["approve", "reject"] | None = None
    history: list[dict[str, str]] = Field(default_factory=list, max_length=12)

    @model_validator(mode="after")
    def exactly_one_input(self) -> "AgentChatRequest":
        has_message = bool(self.message and self.message.strip())
        if has_message == (self.decision is not None):
            raise ValueError("message and decision must contain exactly one input")
        if has_message:
            self.message = self.message.strip()
        return self


class AgentEvent(BaseModel):
    type: Literal[
        "status", "token", "replace", "sources", "approval", "done", "error"
    ]
    data: Any


class SearchRequest(BaseModel):
    kb_id: int = Field(gt=0)
    query: str = Field(min_length=1, max_length=1000)
    top_k: int = Field(default=4, ge=1, le=8)
