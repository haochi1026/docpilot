from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field, model_validator


class AgentChatRequest(BaseModel):
    request_id: str | None = Field(default=None, min_length=8, max_length=120)
    thread_id: str = Field(min_length=1, max_length=120)
    username: str = Field(min_length=1, max_length=64)
    user_id: int = Field(gt=0)
    role: str = Field(min_length=1, max_length=20)
    kb_id: int = Field(gt=0)
    message: str | None = Field(default=None, max_length=4000)
    decision: Literal["approve", "reject"] | None = None
    approval_id: str | None = Field(default=None, min_length=8, max_length=80)
    approval_token: str | None = Field(default=None, min_length=16, max_length=200)
    history: list[dict[str, str]] = Field(default_factory=list, max_length=12)

    @model_validator(mode="after")
    def exactly_one_input(self) -> "AgentChatRequest":
        has_message = bool(self.message and self.message.strip())
        if has_message == (self.decision is not None):
            raise ValueError("message and decision must contain exactly one input")
        if has_message:
            self.message = self.message.strip()
        if self.decision is not None and not self.approval_id:
            raise ValueError("approval_id is required when resuming an interrupted task")
        if self.decision == "approve" and not self.approval_token:
            raise ValueError("approval_token is required for an approved write action")
        return self


class AgentEvent(BaseModel):
    type: Literal[
        "status", "token", "replace", "sources", "approval", "done", "error"
    ]
    data: Any


class AgentEvaluationResponse(BaseModel):
    request_id: str
    trace_id: str | None = None
    trace_exported: bool = False
    status: Literal["SUCCESS", "INTERRUPTED", "ERROR"]
    answer: str = ""
    sources: list[dict[str, Any]] = Field(default_factory=list)
    event_types: list[str] = Field(default_factory=list)
    approval_requested: bool = False
    error: str | None = None
    tool_calls: list[dict[str, Any]] = Field(default_factory=list)


class SearchRequest(BaseModel):
    kb_id: int = Field(gt=0)
    query: str = Field(min_length=1, max_length=1000)
    top_k: int = Field(default=4, ge=1, le=8)
