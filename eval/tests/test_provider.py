"""离线单测：provider 协议、请求体、重试与错误处理，不发出真实网络请求。"""

from __future__ import annotations

import json
import ssl
import unittest
import urllib.error
import urllib.request

from eval.provider import (
    FakeProvider,
    OpenAICompatibleProvider,
    ProviderError,
    Reply,
    _endpoint,
    build_payload,
)


class FakeProviderTest(unittest.TestCase):
    def test_returns_mapped_reply(self):
        provider = FakeProvider({"a": "好的。", "b": "嗯。"})
        reply = provider.complete([{"role": "user", "content": "hi"}], scenario_id="a")
        self.assertIsInstance(reply, Reply)
        self.assertEqual(reply.text, "好的。")
        self.assertEqual(len(provider.calls), 1)

    def test_default_and_missing_id(self):
        provider = FakeProvider(default_response="默认")
        self.assertEqual(provider.complete([], scenario_id="missing").text, "默认")
        self.assertEqual(provider.complete([]).text, "默认")


class PayloadTest(unittest.TestCase):
    def test_endpoint_join(self):
        self.assertEqual(_endpoint("https://api.deepseek.com"), "https://api.deepseek.com/chat/completions")
        self.assertEqual(
            _endpoint("https://api.deepseek.com/v1/"), "https://api.deepseek.com/v1/chat/completions"
        )
        self.assertEqual(
            _endpoint("https://x.test/chat/completions"), "https://x.test/chat/completions"
        )

    def test_payload_shape(self):
        payload = build_payload(
            [{"role": "user", "content": "hi"}],
            model="deepseek-chat",
            temperature=0.75,
            max_tokens=400,
        )
        self.assertEqual(payload["model"], "deepseek-chat")
        self.assertEqual(payload["messages"][0]["content"], "hi")
        self.assertFalse(payload["stream"])
        self.assertEqual(payload["temperature"], 0.75)
        self.assertEqual(payload["max_tokens"], 400)
        self.assertNotIn("tools", payload)


class OpenAIProviderTest(unittest.TestCase):
    def _provider(self, transport, *, retries=1, api_key="test-key") -> OpenAICompatibleProvider:
        return OpenAICompatibleProvider(
            base_url="https://example.test/v1",
            api_key=api_key,
            model="deepseek-chat",
            temperature=0.75,
            max_tokens=100,
            timeout=5,
            retries=retries,
            transport=transport,
            sleep=lambda _seconds: None,
        )

    def test_success_and_usage(self):
        seen: list[dict] = []

        def transport(request: urllib.request.Request, timeout: float, context: ssl.SSLContext):
            seen.append(json.loads(request.data))
            self.assertEqual(request.full_url, "https://example.test/v1/chat/completions")
            self.assertEqual(request.get_header("Authorization"), "Bearer test-key")
            return 200, json.dumps(
                {
                    "model": "deepseek-chat",
                    "choices": [{"message": {"content": "刚忙完。"}}],
                    "usage": {"prompt_tokens": 11, "completion_tokens": 3, "total_tokens": 14},
                }
            )

        reply = self._provider(transport).complete([{"role": "user", "content": "hi"}])
        self.assertEqual(reply.text, "刚忙完。")
        self.assertEqual(reply.usage["total_tokens"], 14)
        self.assertEqual(reply.attempts, 1)
        self.assertEqual(seen[0]["messages"][0]["content"], "hi")

    def test_retries_429_then_succeeds(self):
        attempts: list[int] = []

        def transport(request, timeout, context):
            attempts.append(1)
            if len(attempts) == 1:
                return 429, '{"error":{"message":"rate limited"}}'
            return 200, '{"choices":[{"message":{"content":"ok"}}]}'

        reply = self._provider(transport, retries=1).complete([])
        self.assertEqual(reply.text, "ok")
        self.assertEqual(reply.attempts, 2)
        self.assertEqual(len(attempts), 2)

    def test_does_not_retry_401_and_never_leaks_key(self):
        attempts: list[int] = []

        def transport(request, timeout, context):
            attempts.append(1)
            return 401, '{"error":{"message":"bad key"}}'

        with self.assertRaises(ProviderError) as caught:
            self._provider(transport, retries=2).complete([])
        self.assertEqual(len(attempts), 1)
        self.assertNotIn("test-key", str(caught.exception))

    def test_network_error_retries_then_raises(self):
        attempts: list[int] = []

        def transport(request, timeout, context):
            attempts.append(1)
            raise urllib.error.URLError("offline")

        with self.assertRaises(ProviderError) as caught:
            self._provider(transport, retries=1).complete([])
        self.assertEqual(len(attempts), 2)
        self.assertIn("网络请求失败", str(caught.exception))

    def test_missing_api_key(self):
        with self.assertRaises(ProviderError):
            OpenAICompatibleProvider(api_key="")

    def test_bad_json(self):
        def transport(request, timeout, context):
            return 200, "not json"

        with self.assertRaises(ProviderError):
            self._provider(transport).complete([])


if __name__ == "__main__":
    unittest.main()
