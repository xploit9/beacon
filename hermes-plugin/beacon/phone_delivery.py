"""Hermes phone cron target backed by Beacon's durable mobile outbox."""
import asyncio
import hashlib
import json
import os
import re
import sys
import threading
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


NO_CONVERSATION = "No owned assistant conversation is configured for phone delivery"
THREAD_GONE = "That thread is no longer available"
HOME_CHAT = "phone"  # the platform's home channel: the Beacon chat
_PROVISION_LOCK = threading.Lock()


def _modules():
    return (sys.modules["beacon_mobile"], sys.modules["beacon_native"], sys.modules["beacon_proactive"])


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


def _thread_target(chat_id):
    """The thread id of `deliver: phone:<thread id>`; None for the home channel, which is the Beacon chat."""
    value = chat_id.strip() if isinstance(chat_id, str) else ""
    return None if value in ("", HOME_CHAT) else value


def _provision_conversation(mobile, native, owner, provider, profile):
    """An owner who never opened the Beacon chat still gets a saved chat for scheduled results; their own chat replaces it once saved."""
    with _PROVISION_LOCK:
        stored = mobile.settings(profile).get("scheduled_callback_conversation_id")
        if isinstance(stored, str):
            return stored
        try:
            created = native.provision(owner, provider, profile)
        except Exception:  # shortcut: any Hermes failure keeps today's error; add logging if support needs the cause.
            raise LookupError(NO_CONVERSATION) from None
        registered = mobile.register_assistant_conversation(owner, provider, profile, created, native.verify, provisional=True)
        if not registered:
            raise LookupError(NO_CONVERSATION)
        return registered


def _queue_standalone_result(message, native_job_id, name, chat_id):
    """Queue the notice for the Beacon chat, or for the thread a job targets (`deliver: phone:<thread id>`), whose conversation also keeps the post."""
    mobile, cfg, profile = _profile_state()
    _, native, _ = _modules()
    owner, provider = cfg.get("owner_id"), cfg.get("owner_provider")
    if not owner or not provider:
        raise LookupError(NO_CONVERSATION)
    thread = _thread_target(chat_id)
    conversation = thread or cfg.get("scheduled_callback_conversation_id")
    if not isinstance(conversation, str):
        conversation = _provision_conversation(mobile, native, owner, provider, profile)
    try:
        if thread:
            mobile.ident(thread)
        native.verify(owner, provider, profile, conversation)
    except (LookupError, ValueError):
        if thread:
            raise LookupError(THREAD_GONE) from None
        raise
    bucket = int(time.time() // 300)
    # shortcut: no upstream run ID; keep the five-minute window until both send paths carry run identity.
    # The destination is part of the identity: one run delivering to two threads or profiles keeps a post for each (held_posts is keyed by this value).
    identity = json.dumps([owner, profile, conversation, native_job_id, str(message), bucket])
    digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]
    job_id = "phone-cron-" + digest
    if thread:
        try:
            native.post(owner, provider, profile, conversation, job_id, str(message), name[:120])
        except Exception as exc:
            if type(exc).__name__ not in native.POST_BUSY:
                raise
            # The thread's own turn is running: the post waits, and the watcher adds it and notifies once the turn ends.
            mobile.hold_post(owner, provider, profile, conversation, job_id, str(message), name[:120])
            return "held-" + job_id
    return mobile.enqueue_task_result(owner, profile, conversation, job_id, str(message)[:4000])


def _strip_cron_header(message):
    header = re.match(r"(?s)^Cronjob Response: ([^\r\n]+)\r?\n\(job_id: ([A-Za-z0-9_-]{1,160})\)\r?\n", message)
    if not header:
        raise ValueError("Beacon phone delivery accepts scheduled job results only (Hermes needs cron.wrap_response: true)")
    body = message[header.end():]
    body = re.sub(r"^-{3,}\r?\n(?:\r?\n)?", "", body, count=1)
    return re.sub(r'\n\nTo stop or manage this job, send me a new message \(e\.g\. "stop reminder [^\n]*"\)\.\s*\Z', "", body), header.group(2), header.group(1)


class BeaconPhoneAdapter(BasePlatformAdapter):
    MAX_MESSAGE_LENGTH = 4000
    splits_long_messages = True  # Preserve full result identity before bounding the queued notice preview.

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
            message,job_id,name=_strip_cron_header(content)
        except ValueError as exc:
            return SendResult(success=False, error=str(exc), retryable=False)  # a retry can never pass
        try:
            event = await asyncio.to_thread(_queue_standalone_result,message,job_id,name,chat_id)
            return SendResult(success=True, message_id=event)
        except Exception as exc:
            return SendResult(success=False, error=str(exc)[:240], retryable=True)

    async def get_chat_info(self, chat_id):
        return {"name": "Beacon phone", "type": "dm"}


async def standalone_send(pconfig, chat_id, message, *, thread_id=None, media_files=None, force_document=False):
    if not isinstance(message, str) or not message.strip():
        return {"error": "Refusing to queue an empty phone result"}
    try:
        message,job_id,name=_strip_cron_header(message)
        event = await asyncio.to_thread(_queue_standalone_result,message,job_id,name,chat_id)
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
        name="phone", label="Beacon phone", adapter_factory=BeaconPhoneAdapter,
        check_fn=check_requirements, validate_config=validate_config, is_connected=is_connected,
        required_env=[], install_hint="Beacon queues results for the owned assistant conversation through its durable mobile outbox.",
        env_enablement_fn=lambda: {"home_channel": {"chat_id": "phone", "name": "Beacon phone"}},
        cron_deliver_env_var="PHONE_HOME_CHANNEL", standalone_sender_fn=standalone_send,
        platform_hint="Deliver concise task results to the owner's Beacon assistant conversation and phone notification.",
        emoji="📱", pii_safe=True,
    )
