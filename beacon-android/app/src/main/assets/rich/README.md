# Offline rich rendering

Mermaid 11.17.2, MIT, from npm. The bundle keeps the upstream dependency license comments inline; MERMAID-LICENSE is retained separately. There are no CDN requests at runtime.

Beacon bundles Mermaid itself instead of shipping upstream's `dist/mermaid.min.js`, so that DOMPurify is 3.4.17 (upstream's prebuilt file carries 3.4.12), KaTeX is 0.18.2 (fixing inherited renderer options, GHSA-238p-pmpm-9mq7), and the syntax is lowered for Android 11's original Chromium 83 WebView. The inputs, with a lockfile that pins every dependency, are in `beacon-android/tools/mermaid-bundle/`. From that folder:

```
npm ci --ignore-scripts
npx esbuild entry.mjs --bundle --format=iife --platform=browser --target=chrome80 --minify --legal-comments=inline --outfile=../../app/src/main/assets/rich/mermaid-11.17.2.min.js
node --test security.test.cjs
```

The file must then have the SHA-256 below.

Shipped SHA-256: `567ba79170d5eb3eb3dc8b8fe328a23995dfee85b9332591a56e11961c014bd3`.

KaTeX is MIT-licensed; its complete notice is KATEX-LICENSE. Mermaid uses native MathML where available, which needs no KaTeX stylesheet or font downloads. WebViews without MathML retain Mermaid's unsupported-math message. The override is outside Mermaid's declared KaTeX range; keep the bundled-renderer compatibility tests passing when updating it. Legacy HTML math is not enabled by Beacon.

jsoup 1.23.2 is resolved by Gradle. JSOUP-LICENSE is its MIT notice. The app's HTML allowlist is in RichSandbox.kt; HTML does not enable JavaScript. Mermaid runs only the packaged renderer in an isolated view with no app bridge, file/content access or network loads.

`polyfills.js` bundles only core-js 3.50.0's `actual/object/has-own`, `actual/array/at`, `actual/string/at`, `actual/string/replace-all` and `actual/structured-clone` entries and their required dependencies. These APIs are used by Mermaid but absent from the original Chromium 83 Android WebView. Rebuild with esbuild 0.28.2 (`bundle: true`, `platform: browser`, `target: chrome80`, `minify: true`, `legalComments: inline`). Its MIT notice is CORE-JS-LICENSE. The polyfills execute only inside the isolated diagram renderer.
