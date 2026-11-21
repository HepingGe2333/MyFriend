"""模型 provider：OpenAI 兼容 HTTP 客户端 + 离线 FakeProvider。

只用标准库（``urllib`` / ``ssl`` / ``json``）。没有任何第三方依赖，也不会把请求发到
配置的 base URL 之外。

真实调用：

    provider = OpenAICompatibleProvider(
        base_url="https://api.deepseek.com",
        api_key=os.environ["DEEPSEEK_API_KEY"],
        model="deepseek-chat",
        temperature=0.75,
        max_tokens=400,
        timeout=60,
        retries=2,
    )
    reply = provider.complete(messages, scenario_id="daily-001")

离线调用：

    provider = FakeProvider(responses={"daily-001": "今天也加班？"})
"""

from __future__ import annotations

import json
import os
import random
import socket
import ssl
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

DEFAULT_BASE_URL = "https://api.deepseek.com"
DEFAULT_MODEL = "deepseek-chat"
DEFAULT_TEMPERATURE = 0.75
DEFAULT_MAX_TOKENS = 400
DEFAULT_TIMEOUT = 60.0
DEFAULT_RETRIES = 2

# 常见系统 CA 证书路径。macOS 上 Python.org 安装包默认指向
# /Library/Frameworks/Python.framework/.../etc/openssl/cert.pem，不一定存在；
# 用系统信任库能避免「curl 能通、Python 报 self-signed certificate in chain」。
_CA_CANDIDATES = (
    "/etc/ssl/cert.pem",  # macOS / LibreSSL
    "/etc/ssl/certs/ca-certificates.crt",  # Debian / Ubuntu
    "/etc/pki/tls/certs/ca-bundle.crt",  # RHEL / Fedora
)


class ProviderError(RuntimeError):
    """带可读原因的 provider 失败。消息里永远不含 API key。"""


@dataclass
class ToolCall:
    """一次原生 function calling 返回的调用。

    ``arguments`` 是解析后的 dict（坏 JSON 时为空 dict），``arguments_text`` 保留模型
    给的原始字符串，供回灌 assistant ``tool_calls`` 时原样带回；``error`` 非空表示这条
    调用不能执行（参数不是合法 JSON / 缺少函数名），但仍会记录并回一条 tool 消息。
    """

    id: str
    name: str
    arguments: dict[str, Any] = field(default_factory=dict)
    arguments_text: str = ""
    error: str = ""

    def as_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "id": self.id,
            "name": self.name,
            "arguments": dict(self.arguments),
        }
        if self.arguments_text:
            data["arguments_text"] = self.arguments_text
        if self.error:
            data["error"] = self.error
        return data


@dataclass
class Reply:
    text: str
    model: str = ""
    latency_ms: int = 0
    usage: dict[str, int] = field(default_factory=dict)
    attempts: int = 1
    tool_calls: list[ToolCall] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        return {
            "text": self.text,
            "model": self.model,
            "latency_ms": self.latency_ms,
            "usage": dict(self.usage),
            "attempts": self.attempts,
            "tool_calls": [call.as_dict() for call in self.tool_calls],
        }


def _parse_arguments(raw: Any) -> tuple[dict[str, Any], str, str]:
    """把 tool_call.function.arguments 解析成 ``(dict, 原始文本, 错误)``。

    OpenAI 兼容接口的 arguments 是 JSON **字符串**；少数网关会直接给 dict。
    空字符串按「无参数」处理（stay_silent 这类工具常见）；坏 JSON 不抛异常，
    只标 error，让 simulator 回一条可读的 tool 结果，评测继续。
    """
    if raw is None:
        return {}, "", ""
    if isinstance(raw, dict):
        return dict(raw), json.dumps(raw, ensure_ascii=False), ""
    if not isinstance(raw, str):
        return {}, json.dumps(raw, ensure_ascii=False), "工具参数不是 JSON 对象"
    text = raw.strip()
    if not text:
        return {}, "", ""
    try:
        data = json.loads(text)
    except ValueError:
        return {}, raw, "工具参数不是合法 JSON"
    if not isinstance(data, dict):
        return {}, raw, "工具参数不是 JSON 对象"
    return data, raw, ""


def parse_tool_calls(raw_calls: Any) -> list[ToolCall]:
    """解析 ``choices[0].message.tool_calls``（数组）；形状不对时返回空列表。"""
    if isinstance(raw_calls, dict):
        raw_calls = [raw_calls]
    if not isinstance(raw_calls, list):
        return []
    calls: list[ToolCall] = []
    for index, item in enumerate(raw_calls, start=1):
        if not isinstance(item, dict):
            calls.append(ToolCall(id=f"call_{index}", name="", error="工具调用不是对象"))
            continue
        function = item.get("function")
        if not isinstance(function, dict):
            function = {}
        name = str(function.get("name") or item.get("name") or "").strip()
        arguments, arguments_text, error = _parse_arguments(function.get("arguments"))
        if not name:
            error = error or "工具调用缺少函数名"
        calls.append(
            ToolCall(
                id=str(item.get("id") or f"call_{index}"),
                name=name,
                arguments=arguments,
                arguments_text=arguments_text,
                error=error,
            )
        )
    return calls


class Provider:
    """provider 协议：输入 messages，输出 Reply。"""

    name = "abstract"

    def complete(
        self,
        messages: list[dict[str, Any]],
        *,
        scenario_id: str | None = None,
        tools: list[dict[str, Any]] | None = None,
        tool_choice: str | dict[str, Any] | None = None,
    ) -> Reply:  # pragma: no cover - 抽象方法
        raise NotImplementedError


def _system_ssl_context(ca_bundle: str | None = None) -> ssl.SSLContext:
    explicit = ca_bundle or os.environ.get("EVAL_CA_BUNDLE") or os.environ.get("SSL_CERT_FILE")
    if explicit:
        try:
            return ssl.create_default_context(cafile=explicit)
        except OSError as error:
            raise ProviderError(f"CA bundle 无法读取：{explicit}（{error}）") from error
    for candidate in _CA_CANDIDATES:
        if Path(candidate).is_file():
            try:
                return ssl.create_default_context(cafile=candidate)
            except OSError:
                continue
    return ssl.create_default_context()


def _endpoint(base_url: str) -> str:
    base = (base_url or DEFAULT_BASE_URL).strip().rstrip("/")
    if base.endswith("/chat/completions"):
        return base
    return base + "/chat/completions"


def build_payload(
    messages: list[dict[str, Any]],
    *,
    model: str,
    temperature: float | None,
    max_tokens: int | None,
    reasoning_effort: str | None = None,
    tools: list[dict[str, Any]] | None = None,
    tool_choice: str | dict[str, Any] | None = None,
) -> dict[str, Any]:
    """请求体只放必要字段，和 App 的 ``buildChatCompletionPayload`` 保持同一形状。

    不传 ``tools`` 时请求体与旧版完全一致（老模型/纯文本链路不受影响）。
    """
    payload: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "stream": False,
    }
    if temperature is not None:
        payload["temperature"] = float(temperature)
    if max_tokens is not None:
        payload["max_tokens"] = int(max_tokens)
    if reasoning_effort:
        payload["reasoning_effort"] = reasoning_effort
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = tool_choice or "auto"
    return payload


class OpenAICompatibleProvider(Provider):
    """OpenAI / DeepSeek 兼容的 ``POST /chat/completions`` 客户端。"""

    name = "openai-compatible"

    def __init__(
        self,
        *,
        base_url: str = DEFAULT_BASE_URL,
        api_key: str,
        model: str = DEFAULT_MODEL,
        temperature: float | None = DEFAULT_TEMPERATURE,
        max_tokens: int | None = DEFAULT_MAX_TOKENS,
        timeout: float = DEFAULT_TIMEOUT,
        retries: int = DEFAULT_RETRIES,
        ca_bundle: str | None = None,
        transport: Callable[[urllib.request.Request, float, ssl.SSLContext], tuple[int, str]] | None = None,
        sleep: Callable[[float], None] = time.sleep,
        extra_headers: dict[str, str] | None = None,
        reasoning_effort: str | None = None,
    ) -> None:
        if not (api_key or "").strip():
            raise ProviderError(
                "缺少 API key：设置环境变量 DEEPSEEK_API_KEY，或传 --api-key。"
                "（离线跑请用 --provider fake）"
            )
        self.base_url = base_url
        self._api_key = api_key.strip()
        self.model = model
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.timeout = float(timeout)
        self.retries = max(0, int(retries))
        self.ca_bundle = ca_bundle
        self._transport = transport or self._http_post
        self._sleep = sleep
        self._extra_headers = dict(extra_headers or {})
        self.reasoning_effort = reasoning_effort
        self.last_usage: dict[str, int] = {}

    def complete(
        self,
        messages: list[dict[str, Any]],
        *,
        scenario_id: str | None = None,
        tools: list[dict[str, Any]] | None = None,
        tool_choice: str | dict[str, Any] | None = None,
    ) -> Reply:
        payload = build_payload(
            messages,
            model=self.model,
            temperature=self.temperature,
            max_tokens=self.max_tokens,
            reasoning_effort=self.reasoning_effort,
            tools=tools,
            tool_choice=tool_choice,
        )
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": f"Bearer {self._api_key}",
        }
        headers.update(self._extra_headers)
        request = urllib.request.Request(_endpoint(self.base_url), data=body, headers=headers, method="POST")
        context = _system_ssl_context(self.ca_bundle)
        started = time.monotonic()
        last_error: Exception | None = None
        for attempt in range(1, self.retries + 2):
            try:
                status, raw = self._transport(request, self.timeout, context)
            except urllib.error.HTTPError as error:
                status, raw = int(error.code), _read_error_body(error)
            except (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionError) as error:
                reason = getattr(error, "reason", error)
                last_error = ProviderError(f"网络请求失败（第 {attempt} 次）：{reason}")
                if attempt <= self.retries:
                    self._sleep(self._backoff(attempt))
                continue
            if status >= 400:
                message = self._http_error_message(status, raw)
                # 只有 429 与 5xx 值得重试；401/403/404/400 重试只是浪费时间和钱。
                if status == 429 or 500 <= status < 600:
                    last_error = ProviderError(message)
                    if attempt <= self.retries:
                        self._sleep(self._backoff(attempt))
                    continue
                raise ProviderError(message)
            return self._parse_response(raw, attempt, started)
        raise last_error or ProviderError("请求失败（未知原因）")

    @staticmethod
    def _backoff(attempt: int) -> float:
        return min(8.0, 1.2 * (2 ** (attempt - 1))) + random.uniform(0, 0.3)

    def _http_post(
        self,
        request: urllib.request.Request,
        timeout: float,
        context: ssl.SSLContext,
    ) -> tuple[int, str]:
        try:
            with urllib.request.urlopen(request, timeout=timeout, context=context) as response:
                return int(response.status), response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            # 把 HTTP 错误统一成 (status, body)，交给 complete() 判断要不要重试。
            return int(error.code), _read_error_body(error)

    @staticmethod
    def _http_error_message(status: int, raw: str) -> str:
        detail = ""
        try:
            root = json.loads(raw)
            error = root.get("error") if isinstance(root, dict) else None
            if isinstance(error, dict):
                detail = str(error.get("message") or "")
            elif isinstance(error, str):
                detail = error
            if not detail and isinstance(root, dict):
                detail = str(root.get("message") or "")
        except (ValueError, TypeError):
            detail = (raw or "").strip()[:200]
        suffix = f"：{detail[:300]}" if detail else ""
        return f"模型接口返回 HTTP {status}{suffix}"

    def _parse_response(self, raw: str, attempts: int, started: float) -> Reply:
        try:
            root = json.loads(raw)
        except ValueError as error:
            raise ProviderError(f"接口返回的不是 JSON：{(raw or '')[:200]}") from error
        if not isinstance(root, dict):
            raise ProviderError("接口返回的 JSON 不是对象")
        usage = root.get("usage") or {}
        if isinstance(usage, dict):
            self.last_usage = {
                "prompt_tokens": int(usage.get("prompt_tokens") or 0),
                "completion_tokens": int(usage.get("completion_tokens") or 0),
                "total_tokens": int(usage.get("total_tokens") or 0),
            }
        choices = root.get("choices")
        if not isinstance(choices, list) or not choices:
            raise ProviderError("接口返回里没有 choices")
        message = (choices[0] or {}).get("message") or {}
        content = message.get("content")
        if content is None:
            content = ""
        if not isinstance(content, str):
            content = str(content)
        return Reply(
            text=content,
            model=str(root.get("model") or self.model),
            latency_ms=int((time.monotonic() - started) * 1000),
            usage=dict(self.last_usage),
            attempts=attempts,
            tool_calls=parse_tool_calls(message.get("tool_calls")),
        )


def _read_error_body(error: urllib.error.HTTPError) -> str:
    try:
        return error.read().decode("utf-8", "replace")
    except Exception:  # pragma: no cover - 读错误体失败不该盖住原始错误
        return ""


class FakeProvider(Provider):
    """离线 provider：按 scenario id 回固定文本，用于单测和 ``--fake`` 演示。"""

    name = "fake"

    def __init__(
        self,
        responses: dict[str, str] | None = None,
        *,
        default_response: str = "嗯，知道了。",
        latency_ms: int = 0,
    ) -> None:
        self.responses = dict(responses or {})
        self.default_response = default_response
        self.latency_ms = latency_ms
        self.calls: list[dict[str, Any]] = []

    @classmethod
    def from_fixture(cls, path: str | Path, **kwargs: Any) -> "FakeProvider":
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            raise ProviderError("fake 回复 fixture 必须是 {scenarioId: text} 对象")
        return cls({str(key): str(value) for key, value in data.items()}, **kwargs)

    def complete(
        self,
        messages: list[dict[str, Any]],
        *,
        scenario_id: str | None = None,
        tools: list[dict[str, Any]] | None = None,
        tool_choice: str | dict[str, Any] | None = None,
    ) -> Reply:
        self.calls.append({"scenario_id": scenario_id, "messages": messages, "tools": tools})
        key = scenario_id or ""
        if key in self.responses:
            text = self.responses[key]
        else:
            text = self.default_response
        return Reply(
            text=text,
            model="fake-model",
            latency_ms=self.latency_ms,
            usage={"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
            attempts=1,
        )
