# Cypher for Android

## Install with your agent

On the PC that runs Hermes, give your agent this prompt:

> Install or update the Cypher plugin for my selected Hermes profile by following https://github.com/xploit9/cypher/blob/main/docs/install-with-agent.md. Ask before restarting an active dashboard or changing network settings. Never print or store passwords, tokens, or API keys, and never modify Hermes core.

This repository is private: the PC needs GitHub access to it first (for example `gh auth login`), which Hermes' plugin installer also uses.

Then on your phone:

1. Download and install the latest `Cypher-*.apk` from the repository's [Releases page](https://github.com/xploit9/cypher/releases).
2. Open the Hermes dashboard and select **Cypher**.
3. Scan the displayed QR code with the Cypher app.

Sign in through Hermes' normal OAuth browser flow; if that browser is already signed in, one tap completes sign-in. The same sign-in covers chat, history, plugin pages, calls and Keep connected. On a loopback dashboard with no accounts, scanning the QR signs in immediately.

An Android client for Hermes conversations, tools and voice, built with Kotlin and Jetpack Compose. This is an in-progress private migration. It is not a verified production release.

The existing Cypher shell is retained with one native Hermes OAuth sign-in. The Cypher plugin adds optional phone capabilities; ordinary chat and history stay on Hermes's native routes. See [migration contract](docs/migration-contract.md).

Build from `cypher-android` using the Gradle wrapper with JDK 21 and Android SDK 36. Machine-local configuration and signing material are intentionally excluded. Do not install a migration build over a production app before acceptance.

## Private preview
`./gradlew assembleDebug` produces "Cypher Preview" (`dev.hermes.android.debug`, version `0.4.15-preview`). It installs alongside production Cypher and does not need `owner.local.properties` or signing secrets. Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk` from `cypher-android`. Do not uninstall production. Complete the preview's setup wizard and sign in with native Hermes OAuth. Phone callbacks and live-device acceptance are incomplete. See [docs/preview.md](docs/preview.md) for details and current limits.

## Licensing
Cypher's existing GPLv3 license is preserved in LICENSE. Adapted Hermes-Relay code retains its MIT notice in licenses/Hermes-Relay-MIT.txt. Android library notices are retained in cypher-android/THIRD-PARTY-NOTICES.md.
