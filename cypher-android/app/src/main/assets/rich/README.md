# Offline rich rendering

Mermaid 11.17.2, MIT, distributed through npm as `mermaid/dist/mermaid.min.js`. The bundled distribution includes upstream dependency license comments; MERMAID-LICENSE is retained separately. There are no CDN requests at runtime.

The upstream bundle uses newer syntax than Android 11's original Chromium 83 WebView. Cypher lowers syntax to Chrome 80 with esbuild 0.28.2, preserving legal comments and the top-level export. Rebuild from the upstream file (first verify its hash):

```
npx --yes esbuild@0.28.2 mermaid-upstream.js --target=chrome80 --minify --legal-comments=inline --outfile=mermaid-11.17.2.min.js
```

Upstream SHA-256: `581ed7d74bd9048d0e3a91363927d72ef22942d7722546b27f7cc29e35390eb8`.

Shipped transformed SHA-256: `a44c094fb176e8171eefe194dc24888578da05cbf05de30ac35061494f58af96`.

jsoup 1.23.2 is resolved by Gradle. JSOUP-LICENSE is its MIT notice. The app's HTML allowlist is in RichSandbox.kt; HTML does not enable JavaScript. Mermaid runs only the packaged renderer in an isolated view with no app bridge, file/content access or network loads.

`polyfills.js` bundles only core-js 3.50.0's `actual/object/has-own`, `actual/array/at`, `actual/string/at`, `actual/string/replace-all` and `actual/structured-clone` entries and their required dependencies. These APIs are used by Mermaid but absent from the original Chromium 83 Android WebView. Rebuild with esbuild 0.28.2 (`bundle: true`, `platform: browser`, `target: chrome80`, `minify: true`, `legalComments: inline`). Its MIT notice is CORE-JS-LICENSE. The polyfills execute only inside the isolated diagram renderer.
