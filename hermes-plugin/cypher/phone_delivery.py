"""Hermes phone cron target backed by Cypher's durable mobile outbox."""
import hashlib
import os
import re
import sys
import time

try:
    from gateway.config import Platform
    from gateway.platforms.base import BasePlatformAdapter, SendResult
except ImportError:  # The functions below remain importable in offline plugin tests.
    Platform = None

    class BasePlatformAdapter:
        def __init__(self, *args, **kwargs):
            pass

        def _mark_connected(self):
            pass

        def _mark_disconnected(self):
            pass

    class SendResult:
        def __init__(self, success, message_id=None, error=None, retryable=False):
            self.success, self.message_id, self.error, self.retryable = success, message_id, error, retryable


def _modules():
    return (sys.modules["cypher_mobile"], sys.modules["cypher_native"], sys.modules["cypher_proactive"])


def _profile_state():
    mobile, _, proactive = _modules()
    cfg = mobile.settings()
    profile = proactive.current_profile(cfg)
    return mobile, mobile.settings(profile), profile


def check_requirements():
    return True


def validate_config(config):
    return True


def is_connected(config):
    try:
        mobile, cfg, _ = _profile_state()
        return bool(cfg.get("owner_id") and cfg.get("owner_provider"))
    except Exception:
        return False


def _queue_standalone_result(message, is_cron=False):
    mobile, cfg, profile = _profile_state()
    _, native, _ = _modules()
    owner, provider = cfg.get("owner_id"), cfg.get("owner_provider")
    conversation = cfg.get("scheduled_callback_conversation_id")
    if not owner or not provider or not isinstance(conversation, str):
        raise LookupError("No owned assistant conversation is configured for phone delivery")
    native.verify(owner, provider, profile, conversation)
    bucket = int(time.time() // 300)
    digest = hashlib.sha256((str(message) + ":" + str(bucket)).encode("utf-8")).hexdigest()[:32]
    job_id = ("phone-cron-" if is_cron else "phone-hermes-") + digest
    return mobile.enqueue_task_result(owner, profile, conversation, job_id, str(message)[:4000])


def _strip_cron_header(message):
    header = re.match(r"(?s)^Cronjob Response: [^\r\n]{1,240}\r?\n\(job_id: [A-Za-z0-9_-]{1,160}\)\r?\n", message)
    if not header:
        return message, False
    body = message[header.end():]
    body = re.sub(r"^-{3,}\r?\n(?:\r?\n)?", "", body, count=1)
    return re.sub(r'\n\nTo stop or manage this job, send me a new message \(e\.g\. "stop reminder [^\n]*"\)\.\s*\Z', "", body), True


class CypherPhoneAdapter(BasePlatformAdapter):
    MAX_MESSAGE_LENGTH = 4000

    def __init__(self, config):
        if Platform is None:
            super().__init__(config=config)
        else:
            super().__init__(config=config, platform=Platform("phone"))

    async def connect(self, *, is_reconnect=False):
        if not is_connected(self.config):
            return False
        self._mark_connected()
        return True

    async def disconnect(self):
        self._mark_disconnected()

    async def send(self, chat_id, content, reply_to=None, metadata=None):
        if not isinstance(content, str) or not content.strip():
            return SendResult(success=False, error="Refusing to queue an empty phone result")
        try:
            message,is_cron=_strip_cron_header(content)
            event = _queue_standalone_result(message,is_cron)
            return SendResult(success=True, message_id=event)
        except Exception as exc:
            return SendResult(success=False, error=str(exc)[:240], retryable=True)

    async def get_chat_info(self, chat_id):
        return {"name": "Cypher phone", "type": "dm"}


async def standalone_send(pconfig, chat_id, message, *, thread_id=None, media_files=None, force_document=False):
    if not isinstance(message, str) or not message.strip():
        return {"error": "Refusing to queue an empty phone result"}
    try:
        message,is_cron=_strip_cron_header(message)
        event = _queue_standalone_result(message,is_cron)
        return {"success": True, "platform": "phone", "chat_id": chat_id or "phone", "message_id": event}
    except Exception as exc:
        return {"error": str(exc)[:240]}


def register(ctx):
    register_platform = getattr(ctx, "register_platform", None)
    if not callable(register_platform):
        return
    if not os.environ.get("PHONE_HOME_CHANNEL", "").strip():
        os.environ["PHONE_HOME_CHANNEL"] = "phone"
    register_platform(
        name="phone", label="Cypher phone", adapter_factory=CypherPhoneAdapter,
        check_fn=check_requirements, validate_config=validate_config, is_connected=is_connected,
        required_env=[], install_hint="Cypher queues results for the owned assistant conversation through its durable mobile outbox.",
        env_enablement_fn=lambda: {"home_channel": {"chat_id": "phone", "name": "Cypher phone"}},
        cron_deliver_env_var="PHONE_HOME_CHANNEL", standalone_sender_fn=standalone_send,
        platform_hint="Deliver concise task results to the owner's Cypher assistant conversation and phone notification.",
        emoji="📱", pii_safe=True,
    )
