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
class Reply:
    text: str
    model: str = ""
    latency_ms: int = 0
    usage: dict[str, int] = field(default_factory=dict)
    attempts: int = 1

    def as_dict(self) -> dict[str, Any]:
        return {
            "text": self.text,
            "model": self.model,
            "latency_ms": self.latency_ms,
            "usage": dict(self.usage),
            "attempts": self.attempts,
        }


class Provider:
    """provider 协议：输入 messages，输出 Reply。"""

    name = "abstract"

    def complete(
        self,
        messages: list[dict[str, str]],
        *,
        scenario_id: str | None = None,
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
    messages: list[dict[str, str]],
    *,
    model: str,
    temperature: float | None,
    max_tokens: int | None,
) -> dict[str, Any]:
    """请求体只放必要字段，和 App 的 ``buildChatCompletionPayload`` 保持同一形状。"""
    payload: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "stream": False,
    }
    if temperature is not None:
        payload["temperature"] = float(temperature)
    if max_tokens is not None:
        payload["max_tokens"] = int(max_tokens)
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
        self.last_usage: dict[str, int] = {}

    def complete(
        self,
        messages: list[dict[str, str]],
        *,
        scenario_id: str | None = None,
    ) -> Reply:
        payload = build_payload(
            messages,
            model=self.model,
            temperature=self.temperature,
            max_tokens=self.max_tokens,
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
        messages: list[dict[str, str]],
        *,
        scenario_id: str | None = None,
    ) -> Reply:
        self.calls.append({"scenario_id": scenario_id, "messages": messages})
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
