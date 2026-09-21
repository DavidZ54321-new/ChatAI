# Third-Party Licenses

## Kai — LaTeX math renderer (vendored)

- **Files:** `app/src/main/java/com/zcw/chatai/ui/md/latex/MathAtom.kt`,
  `MathParser.kt`, `MathRenderer.kt`, `MathSymbols.kt`
- **Source:** https://github.com/SimonSchubert/Kai
- **Upstream path:** `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/ui/markdown/math/`
- **Retrieved from:** branch `main`
- **Branch head at retrieval:** `7cab850fb1914d703fbee6f96f02d21c21810b4d` (2026-09-16)
- **Last upstream commit touching this directory:** `c966468dce38dd1337dcfd4fe000e1b68b14eea0` (2026-08-30)
- **License:** Apache-2.0
- **Copyright:** held by the Kai project authors

`app/src/main/java/com/zcw/chatai/ui/md/latex/` is a **derivative copy** of the upstream
files above. The only modifications are the `package` declaration
(`com.inspiredandroid.kai.ui.markdown.math` -> `com.zcw.chatai.ui.md.latex`) and a one-line
attribution header prepended to each file. The rendering and parsing algorithms are
unchanged. Every file carries the header:

```
// Vendored from https://github.com/SimonSchubert/Kai (Apache-2.0). See THIRD_PARTY_LICENSES.md.
```

Licensed under the Apache License, Version 2.0. The full license text is available at:
https://www.apache.org/licenses/LICENSE-2.0

The upstream license text is published at
https://github.com/SimonSchubert/Kai/blob/main/LICENSE.txt

## PlantUML TeaVM engine — `@plantuml/core` (bundled)

- **Files:** `app/src/main/assets/plantuml/plantuml.js`,
  `app/src/main/assets/plantuml/viz-global.js`,
  `app/src/main/assets/plantuml/LICENSE-plantuml-core.txt`
- **Source:** https://github.com/plantuml/plantuml (published on npm as
  [`@plantuml/core`](https://www.npmjs.com/package/@plantuml/core))
- **Version / tarball:** `@plantuml/core@1.2026.6`
  (`https://registry.npmjs.org/@plantuml/core/-/core-1.2026.6.tgz`,
  integrity `sha512-e+s8jtAKT6kb7yvOCXv0exXOp7FvyKYDcpv+aQrThwXUQgdTGP+fb9hPZQr7jbeEMuUHbxjH1HYLwNZZxw3hJg==`)
- **Retrieved:** 2026-09-21
- **License:** MIT
- **Copyright:** (C) 2009-2024, Arnaud Roques (PlantUML)
- **Bundled:** `plantuml.js` (PlantUML TeaVM-compiled engine, ES module),
  `viz-global.js` (Viz.js 3.24.0 — Graphviz layout engine, classic script).
  `emoji.js` / `openiconic.js` are **not** bundled (only needed for sprite syntax).

**Pin ≥ `1.2026.6`.** PlantUML itself is GPL-3.0, but the npm package is
assembled from the separate **MIT license flavor** (`plantuml-mit` subproject) —
only since `1.2026.6`. Earlier versions of `@plantuml/core` were
GPL-3.0-or-later and **must not** be bundled into this app. Do not downgrade.

The bundled `LICENSE-plantuml-core.txt` is the upstream MIT license text shipped
inside the npm package. Full text: https://opensource.org/licenses/MIT

