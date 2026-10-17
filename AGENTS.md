# Cypher private migration

This tree is the isolated Cypher Android migration, not the original dirty source checkout.
Keep Cypher navigation, tabs, drawer, profiles, Bot Mode and settings. Standard sign-in/chat/history must use unmodified native Hermes, never require an optional plugin. Optional Relay pairing has separate credentials and capabilities. Preserve HTTPS/WSS, owner/profile/conversation authority and upstream licenses. No retained audio. No core patches, startup-hook or monkeypatch workarounds.

One coordinator integrates. Concurrent workers use assigned branches/worktrees and exact file ownership; do not edit shared ViewModel, Activity, application, manifest, Gradle or shared model files unless assigned. Do not run Gradle, ADB, installs, deployment or an LLM review as a worker; the integrator serializes those lanes. Read docs/migration-contract.md.

Private GitHub xploit9/cypher is the only publication target. Never use Gitea, change visibility or submit upstream PRs. Every push requires current independent pinned OCR and Gitleaks of the final tree/outgoing history. Do not push as a worker. No operational logs, private handoffs, credentials, toolchains, signing keys or recordings in Git. Candidate builds are separate; production replacement is held for physical-phone acceptance and original signer/data-preservation verification.
