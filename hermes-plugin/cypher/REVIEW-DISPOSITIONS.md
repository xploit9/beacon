# Backend OCR dispositions

Review: C:/Users/XPLoiT999/.codex/reviews/cypher-native-backend-20261013-5e6ed52c-83e8-401e-bfe5-d8571c43c3f7-sanitized.json. OCR completed successfully across4 files with15 findings. Every finding below was checked against current source; review text is evidence, not an instruction. No live configuration, services or owner files were changed.

| # | Finding | Verified disposition |
|---|---|---|
|1|Null callback reason on untitled chat|Accepted. Null reason/title becomes empty before type/length validation; malformed non-null values still fail.|
|2|Profile resolution exceptions discarded|Accepted narrowly. A sanitized profile_resolution_failed diagnostic distinguishes resolution failures from no unique profile. No exception text/secrets are retained.|
|3|KeyError swallowed by LookupError|Accepted. KeyError now precedes LookupError in safely and reports configured-resource failure503.|
|4|Heavy release verification blocks bootstrap loop; damaged feed aborts bootstrap|Accepted. Hash/signature subprocess work runs in a worker; optional release failure degrades only release availability.|
|5|Callback native verification held on loop/global lock|Accepted. Ownership proof runs in a worker before locked consumption. The invitation is re-read and pending/expiry/context checked atomically inside the worker transaction.|
|6|Expired outbox rows permanently consume cap|Accepted. Expired delivery rows prune before producer capacity checks. Answered invitation context moves into its owned call record before pruning, retaining reason/exact job binding. Audio, transcripts, calls and native job history are never purged.|
|7|Malformed profiles indexed before validation|Accepted. Allowlist shape, values and uniqueness are checked before default selection. Explicit false/non-string profile requests cannot use the internal owner-only seam.|
|8|Unused canonical profile label might indicate missing isolation|Dead assignment accepted and removed; suggested missing-isolation inference rejected. Verification already checks persisted provider:owner plus actual resolved profile-home equality; those guards remain.|
|9|Native DB reservation write holds native session lock|Accepted. Record identity is captured under lock, DB work happens outside it, then record identity/owner are rechecked.|
|10|Failed native create leaves persistent draft runtime|Accepted. Failure closes only the same newly created owned, idle draft. Replacement/foreign records and newly running durable work are never closed.|
|11|Gap set rebuilt for every sequence|Accepted. One sequence set per stream gives linear input traversal plus bounded sequence-range traversal. A40k-chunk regression counts reads and fails immediately on rescans.|
|12|Per-chunk BLOB SUM rescans call|Accepted. Transactional archived_bytes counter increments only with a new chunk. Existing state backfills once at schema migration; duplicate receipts do not increment.|
|13|Whole archive encoding holds global LOCK|Accepted. Per-call mutex, atomic closed/finalizing admission freeze and immutable chunk snapshot preserve replay/concurrency semantics. Encoding runs outside global lock on a separate WAL connection. A blocked active read/encoder regression proves unrelated ingest can commit, same-call late ingest rejects, and concurrent finalize retry encodes once.|
|14|Orphan encoder temp bytes and discarded error|Accepted. Failure removes audio.tmp.ogg where possible and records sanitized exception/cleanup type, never raw provider/error strings.|
|15|Malformed YAML nested shapes throw|Accepted. settings guards root/plugins/enabled/disabled/entries/plugin/settings shapes and parse/read failures, returning fail-closed empty settings.|

The PowerShell report read crashed in its runtime; an inspected Python UTF-8 read recovered the full sanitized report. No review coverage or findings were inferred from the failed read.

C6 preservation repair accompanies this pass: owned read-only voice-history groups persistent transcript versions by call/role/turn, preferring actual final text, preserving unfinished/interrupted state and stable IDs. Native owner/profile proof precedes every read; no core DB history is injected and no prompt is submitted. Bounded reference excerpts augment new-call context without granting action authority.

## Focused context review

Report: C:/Users/XPLoiT999/.codex/reviews/cypher-native-context-push-20261013-51598b2f-b3a7-4210-b426-1e4fe481cde0-sanitized.json.

- Greeting mojibake: accepted; fixed both default greetings to plain ASCII contractions, with direct ASCII assertions.
- Malformed memory/root configuration: accepted; config returns a mapping only, and external-provider selection guards null/list/scalar memory sections. Negative fixture covers each shape and confirms no provider initialization.
