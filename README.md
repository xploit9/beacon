# Cypher for Android

An Android client for Hermes conversations, tools and voice, built with Kotlin and Jetpack Compose. This is an in-progress private migration. It is not a verified production release.

The existing Cypher shell is retained while standard Hermes connectivity and optional paired extensions are separated. See [migration contract](docs/migration-contract.md).

Build from `cypher-android` using the Gradle wrapper with JDK 21 and Android SDK 36. Machine-local configuration and signing material are intentionally excluded. Do not install a migration build over a production app before acceptance.

## Licensing
Cypher's existing GPLv3 license is preserved in LICENSE. Adapted Hermes-Relay code retains its MIT notice in licenses/Hermes-Relay-MIT.txt. Android library notices are retained in cypher-android/THIRD-PARTY-NOTICES.md.
