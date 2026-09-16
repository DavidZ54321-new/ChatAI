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
