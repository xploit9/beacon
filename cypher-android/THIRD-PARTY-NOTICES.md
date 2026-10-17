# Third party notices

This native client is distributed under GNU GPL version 3 only, with its complete corresponding source in the accompanying source package. See LICENSE. The private owner signing key and passwords are excluded from that package.

AndroidX, Kotlin and the Android platform use their respective project licenses; CommonMark uses BSD-2-Clause; OkHttp uses Apache-2.0; Firebase client libraries carry their published notices.

The native math renderer is `ru.noties:jlatexmath-android:0.2.0`, Copyright its JLaTeXMath and Android-port authors, licensed under GNU GPL version 2 or later with the upstream independent-module linking exception. Exact source: https://github.com/noties/jlatexmath-android/tree/cc2a83dcf695028b5588f91a90734d94eb5bd02b (tag `v0.2.0`). The Maven sources JAR and full upstream source archive, LICENSE, COPYING and font licenses are retained in the accompanying `third-party/jlatexmath-android-0.2.0` source package. Their SHA-256 hashes are recorded in its `provenance.json`. Do not remove notices when distributing the APK/source.

The remote terminal reuses Java terminal-emulator classes from Termux `v0.118.3`, commit `5b657c6adf4304e5198951ce815fe0205dcac29c`. The native client package preserves the GPL-3.0-only project license and upstream Apache-2.0 exceptions for the original Terminal Emulator for Android code. Exact original source, license statement, COPYING and hashes are in `third-party/termux-terminal-emulator-0.118.3`; Hermes changes are included in the native client source. This uses the existing server PTY; no Termux local shell, JNI runtime or NDK component is distributed.

Native static SVG previews use `com.caverock:androidsvg-aar:1.4`, Copyright Paul LeBeau, Cave Rock Software Ltd., under Apache-2.0. The exact Maven sources JAR, POM, copyright and license are retained in `third-party/androidsvg-1.4`, with hashes in its `provenance.json`. Project: https://github.com/BigBadaboom/androidsvg.

Build dependencies are pinned in `app/build.gradle.kts`; the Gradle resolution output records the actual resolved graph. No upstream brand assets or proprietary system font are copied into this app.

The direct Gemini wire patterns are adapted from [Hermes Conduit](https://github.com/kaishi00/hermes-conduit), revision `c13e234ff597756fc4511381ebbb1c998dd5ab66`, Copyright (c) 2026 kaishi00, under the MIT License. Its complete license is bundled at `app/src/main/assets/licenses/conduit-LICENSE.txt` and retained in this corresponding source. The Android transport, native dashboard extension and device media lifecycle are implemented in this project.
