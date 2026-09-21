---
version: alpha
name: ChatGPT-design-analysis
description: The product-surface design analysis of OpenAI's ChatGPT. It is a deliberately monochrome system — pure black and white with a single cool-gray-neutral ramp — where colour is absent from the UI chrome entirely and is reserved for content imagery and a blue focus ring. Primary actions are black pills (white pills in dark mode); the ChatGPT product is dark-native (#212121 canvas, #171717 sunken sidebar, #2f2f2f raised cards/bubbles). Typography is a single sans family (OpenAI Sans, or the platform system font inside the apps) at a 17px body size and never heavier than weight 600; SF Mono carries code. Shapes are soft and pill-led: 9999px for buttons, chips and the composer, 6–24px for cards. The design rejects gradients, coloured chrome, decorative shadows and warm tints.

colors:
  primary: "#000000"
  primary-inverse: "#ffffff"
  ink: "#000000"
  body: "#333333"
  muted: "#767881"
  muted-soft: "#8e8e93"
  canvas: "#ffffff"
  surface: "#f1f1f1"
  surface-strong: "#e5e5e5"
  hairline: "#dddee1"
  hairline-soft: "#ececec"
  focus-ring: "#3b82f680"
  surface-dark: "#212121"
  surface-dark-sunken: "#171717"
  surface-dark-raised: "#2f2f2f"
  surface-dark-high: "#383838"
  code-background: "#0d0d0d"
  on-dark: "#ececec"
  on-dark-muted: "#b4b4b4"
  on-dark-soft: "#8f8f8f"
  dark-border: "#3e3e42"
  success: "#10a37f"
  warning: "#f5a623"
  error: "#ef4146"

typography:
  display-lg:
    fontFamily: "OpenAI Sans, Söhne, Inter, system-ui, sans-serif"
    fontSize: 48px
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: -0.03em
  display-md:
    fontFamily: "OpenAI Sans, Söhne, Inter, system-ui, sans-serif"
    fontSize: 28px
    fontWeight: 500
    lineHeight: 1.3
    letterSpacing: -0.02em
  title-lg:
    fontFamily: "OpenAI Sans, Söhne, Inter, system-ui, sans-serif"
    fontSize: 22px
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: -0.01em
  title-md:
    fontFamily: "OpenAI Sans, Söhne, Inter, system-ui, sans-serif"
    fontSize: 18px
    fontWeight: 500
    lineHeight: 1.5
    letterSpacing: 0
  body-md:
    fontFamily: "OpenAI Sans, Söhne, Inter, system-ui, sans-serif"
    fontSize: 17px
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0
  body-sm:
    fontFamily: "OpenAI Sans, Söhne, Inter, system-ui, sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.55
    letterSpacing: 0
  caption:
    fontFamily: "OpenAI Sans, Söhne, Inter, system-ui, sans-serif"
    fontSize: 13px
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: 0.01em
  button:
    fontFamily: "OpenAI Sans, Söhne, Inter, system-ui, sans-serif"
    fontSize: 17px
    fontWeight: 500
    lineHeight: 1
    letterSpacing: 0
  code:
    fontFamily: "SF Mono, ui-monospace, MonoCode, monospace"
    fontSize: 16px
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0

rounded:
  sm: 6px
  md: 24px
  input: 40px
  pill: 9999px
  full: 9999px

spacing:
  xxs: 4px
  xs: 8px
  sm: 12px
  md: 16px
  lg: 24px
  xl: 32px
  xxl: 48px
  section: 64px

components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.primary-inverse}"
    typography: "{typography.button}"
    rounded: "{rounded.pill}"
    padding: 12px 24px
  button-primary-hover:
    backgroundColor: "#333333"
    textColor: "{colors.primary-inverse}"
    rounded: "{rounded.pill}"
  button-primary-disabled:
    backgroundColor: "{colors.muted}"
    textColor: "{colors.primary-inverse}"
    rounded: "{rounded.pill}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.button}"
    rounded: "{rounded.pill}"
    padding: 10px 20px
  button-ghost:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.button}"
    rounded: "{rounded.sm}"
    padding: 8px 12px
  composer:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body-md}"
    rounded: "{rounded.pill}"
    padding: 16px 24px
  composer-dark:
    backgroundColor: "{colors.surface-dark-raised}"
    textColor: "{colors.on-dark}"
    rounded: "{rounded.pill}"
  bubble-user:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body-md}"
    rounded: "{rounded.md}"
  bubble-user-dark:
    backgroundColor: "{colors.surface-dark-raised}"
    textColor: "{colors.on-dark}"
    rounded: "{rounded.md}"
  sidebar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
  sidebar-dark:
    backgroundColor: "{colors.surface-dark-sunken}"
    textColor: "{colors.on-dark}"
    typography: "{typography.body-sm}"
  code-block:
    backgroundColor: "{colors.code-background}"
    textColor: "{colors.on-dark}"
    typography: "{typography.code}"
    rounded: "{rounded.sm}"
    padding: 16px
  card:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.title-md}"
    rounded: "{rounded.md}"
    padding: 24px
  text-input:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body-md}"
    rounded: "{rounded.input}"
    padding: 16px 24px
---

## Overview

ChatGPT is a **monochrome** product. Where every other AI brand reaches for a signature hue, ChatGPT's chrome is black, white and a single cool-gray ramp — and nothing else. Colour enters the product through one door: **content** (model output, generated and user imagery) and one technical affordance, a **blue focus ring** (`{colors.focus-ring}` — `#3b82f680`, used for keyboard focus outlines only). There is no coloured button, no tinted card, no brand-green accent anywhere in the UI.

The style's whole argument is restraint: *the model's output is the only thing on stage*. The measured tokens on openai.com are unambiguous — primary action `#000000`, page `#ffffff`, secondary surface `#f1f1f1`, muted text `#767881`, hairline `#dddee1` — and the system's own instruction is blunt: **"Don't introduce new colors to the UI; rely on the monochrome palette."** The OpenAI teal (`#10a37f`) belongs to the logo/marketing mark, not the product surface.

The product is **dark-native**: `{colors.surface-dark}` (#212121) canvas, `{colors.surface-dark-sunken}` (#171717) sidebar, `{colors.surface-dark-raised}` (#2f2f2f) cards/bubbles/composer. In dark mode the monochrome inverts — primary actions become a **white pill with black text**.

**Key Characteristics:**
- Monochrome UI: `{colors.ink}` #000000 / `{colors.canvas}` #ffffff, plus a neutral gray ramp. No chromatic accent in chrome.
- Primary action is a **black pill** (`{colors.primary}`) in light, a white pill in dark. Radius always `{rounded.pill}` (9999px).
- Secondary surface `{colors.surface}` (#f1f1f1); hairlines `{colors.hairline}` (#dddee1); muted text `{colors.muted}` (#767881).
- Single sans family — OpenAI Sans / the platform system font — body 17px / weight 400. Weights never exceed 600; display and headings sit at 500.
- Dark-native: #212121 → #171717 → #2f2f2f → #383838 is the elevation ladder.
- Flat by default: depth comes from 1px borders and surface tone, not shadows.
- The composer is the hero — a large, fully-rounded (`{rounded.pill}`) field.

## Colors

### Brand & Interactive
- **Black** (`{colors.primary}` — #000000): the primary action colour. Fills the main CTA ("Try ChatGPT"), body text, and the logo. There is no separate brand accent.
- **White** (`{colors.primary-inverse}` — #ffffff): text on black, and the primary action fill in dark mode.
- **Black Hover** (#333333): lightened black for hover on black-filled elements.
- **Blue Focus Ring** (`{colors.focus-ring}` — `#3b82f680`): keyboard focus outlines only. The single chromatic value in the UI.

### Surface (Light)
- **Canvas** (`{colors.canvas}` — #ffffff): pure white page floor.
- **Surface** (`{colors.surface}` — #f1f1f1): secondary surfaces — nav hover, cookie banner, "Get started" bands.
- **Surface Strong** (`{colors.surface-strong}` — #e5e5e5): emphasised neutral fills.
- **Hairline** (`{colors.hairline}` — #dddee1): borders on inputs, cards, dividers.
- **Hairline Soft** (`{colors.hairline-soft}` — #ececec): in-band dividers.

### Surface (Dark — the native mode)
- **Surface Dark** (`{colors.surface-dark}` — #212121): main canvas.
- **Surface Dark Sunken** (`{colors.surface-dark-sunken}` — #171717): sidebar, deeper panels.
- **Surface Dark Raised** (`{colors.surface-dark-raised}` — #2f2f2f): cards, bubbles, composer.
- **Surface Dark High** (`{colors.surface-dark-high}` — #383838): strongest raised elevation.
- **Code Background** (`{colors.code-background}` — #0d0d0d): code blocks, both modes.
- **Dark Border** (`{colors.dark-border}` — #3e3e42): hairline dividers on dark.

### Text
- **Ink** (`{colors.ink}` — #000000): all primary text on light; 21:1 on white (AAA).
- **Body** (`{colors.body}` — #333333): default running text.
- **Muted** (`{colors.muted}` — #767881): secondary text — metadata, footer links, timestamps.
- **Muted Soft** (`{colors.muted-soft}` — #8e8e93): tertiary text, placeholder, disabled.
- **On Dark** (`{colors.on-dark}` — #ececec): body text on dark.
- **On Dark Muted** / **Soft** (`{colors.on-dark-muted}` #b4b4b4 / `{colors.on-dark-soft}` #8f8f8f).

### Semantic
- **Success** (`{colors.success}` — #10a37f): the brand teal reappears only here, as a status colour.
- **Warning** (`{colors.warning}` — #f5a623): caution / rate-limit.
- **Error** (`{colors.error}` — #ef4146): destructive actions, validation.

## Typography

### Font Family
The product runs **OpenAI Sans**, and inside the apps it inherits the **platform-native stack** (SF Pro on iOS, the system sans on Android) to respect system sizing. The fallback walks `Söhne, Inter, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`. Code and identifiers use **SF Mono** (`ui-monospace, Menlo, Consolas`).

The system is **sans-only** on the product surface. There is no serif in UI chrome.

### Hierarchy

| Token | Size | Weight | Line Height | Letter Spacing | Use |
|---|---|---|---|---|---|
| `{typography.display-lg}` | 48px | 600 | 1.2 | -0.03em | Hero headline |
| `{typography.display-md}` | 28px | 500 | 1.3 | -0.02em | Section titles |
| `{typography.title-lg}` | 22px | 500 | 1.4 | -0.01em | Sub-headings, card titles |
| `{typography.title-md}` | 18px | 500 | 1.5 | 0 | Minor headings, labels |
| `{typography.body-md}` | 17px | 400 | 1.5 | 0 | Default running text |
| `{typography.body-sm}` | 14px | 400 | 1.55 | 0 | Secondary UI, sidebar rows |
| `{typography.caption}` | 13px | 400 | 1.4 | 0.01em | Metadata, legal, timestamps |
| `{typography.button}` | 17px | 500 | 1.0 | 0 | Button labels |
| `{typography.code}` | 16px | 400 | 1.5 | 0 | Code — SF Mono |

### Principles
Hierarchy comes from **size and weight**, never colour. Body is 400, headings 500, display 600 — **700 is never used** on the UI face (it is loaded only for the serif/KaTeX faces, and applying it to the sans produces a synthesised fake-bold). Headings carry negative tracking (−0.01em to −0.03em); functional type (`button`, nav, meta) sits at 0. Mono for machine truth: code, keys, token counts and model IDs.

## Layout

### Spacing System
- **Base unit:** 4px. Scale: `[4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 96, 128]`.
- **Tokens:** `{spacing.xxs}` 4 · `{spacing.xs}` 8 · `{spacing.sm}` 12 · `{spacing.md}` 16 · `{spacing.lg}` 24 · `{spacing.xl}` 32 · `{spacing.xxl}` 48 · `{spacing.section}` 64.
- 24px is the standard card padding; 64–96px is the vertical rhythm between major sections.

### Grid & Container
- **Max content width:** ~1280px, centred.
- **Conversation column:** centred single column; the sidebar is a fixed rail on desktop and a drawer on mobile.
- Whitespace, not dividers, separates sections; borders appear only where structure needs them.

## Elevation & Depth

| Level | Treatment | Use |
|---|---|---|
| Flat | No shadow, no border | Default state for everything |
| Hairline | 1px `{colors.hairline}` / `{colors.dark-border}` | Inputs, cards, dividers |
| Sunken | `{colors.surface-dark-sunken}` / `{colors.surface}` | Sidebar, deeper panels |
| Raised | `{colors.surface-dark-raised}` / `{colors.surface}` | Bubbles, cards, composer (dark) |
| Hover lift | `translateY(-4px)` + faint shadow | Interactive cards, on hover only |

Depth is **border-first, shadow-rare**. Default surfaces are flat; a barely-visible shadow appears only to signal hover on an interactive card. The dark surface ladder (#171717 → #212121 → #2f2f2f → #383838) carries elevation in dark mode.

## Shapes

| Token | Value | Use |
|---|---|---|
| `{rounded.sm}` | 6px | Inline/ghost controls, small containers, code blocks |
| `{rounded.md}` | 24px | Content cards, message bubbles |
| `{rounded.input}` | 40px | The large search/composer field |
| `{rounded.pill}` | 9999px | Buttons, chips, selectors, composer |
| `{rounded.full}` | 9999px / 50% | Avatars, icon buttons |

Pill-shaped controls are a core brand signal — buttons and inputs are never 8px. There is no decorative imagery in the system; imagery, when present, is content.

## Components

### Buttons
**`button-primary`** — the black pill. Background `{colors.primary}` (#000000), text `{colors.primary-inverse}` (white), `{typography.button}`, `{rounded.pill}`, padding 12px 24px. Hover lightens to `#333333`. In dark mode the same component inverts to a white pill with black text.
**`button-secondary`** — light-gray pill: background `{colors.surface}` (#f1f1f1), text `{colors.ink}`, 1px `{colors.hairline}` border, `{rounded.pill}`.
**`button-ghost`** — transparent, text-only, `{colors.surface}` background on hover.

### Composer
**`composer`** — the large, fully-rounded input. Background `{colors.canvas}` (light) / `{colors.surface-dark-raised}` (dark), 1px `{colors.hairline}` border, `{rounded.input}`–`{rounded.pill}`, padding 16px 24px. A circular send affordance sits at the trailing edge.

### Messages
**`bubble-user`** — right-aligned bubble, background `{colors.surface}` (#f1f1f1) light / `{colors.surface-dark-raised}` (#2f2f2f) dark, text `{colors.ink}` / `{colors.on-dark}`, `{rounded.md}`. The bubble is neutral gray — **never tinted with an accent**.
**Assistant turns** — no bubble; text on the canvas, inline code as a soft chip, fenced code in `code-block`.

### Code block
**`code-block`** — background `{colors.code-background}` (#0d0d0d), text `{colors.on-dark}`, `{typography.code}` (SF Mono), `{rounded.sm}`, padding 16px, with a copy affordance.

### Sidebar
**`sidebar`** — profile row, new-chat affordance, conversation list. Background `{colors.surface}` (light) / `{colors.surface-dark-sunken}` (#171717, dark). Active row takes `{colors.surface}` / `{colors.surface-dark-raised}` with `{rounded.sm}`.

### Icons & imagery
System icons or custom iconography that fits ChatGPT's world — **monochromatic and outlined**. Partner logos are appended by the platform before a widget renders; they are never part of the response body.

## Do's and Don'ts

### Do
- Keep the chrome **monochrome**. Colour is content; the UI is black, white and gray.
- Use `{colors.primary}` (black) for primary actions and `{colors.primary-inverse}` (white) in dark mode. Buttons are pills.
- Use `{colors.muted}` (#767881) for secondary/metadata text and `{colors.hairline}` (#dddee1) for borders.
- Design dark mode as the native mode; treat light as the derived one.
- Separate regions with whitespace, or with 1px hairlines at most.
- Reserve `{colors.success}` / `{colors.warning}` / `{colors.error}` for genuine status.
- Render code and identifiers in `{typography.code}` (mono).

### Don't
- **Don't introduce new colours to the UI** — no brand green, no tinted cards, no coloured chrome.
- Don't colour the background of text areas, and don't let an accent override backgrounds or text colours.
- Don't add gradients, glows or decorative illustration.
- Don't use warm tints; everything is cool neutral or pure black/white.
- Don't bold the sans face; max weight is 600, headings sit at 500.
- Don't use serif on the product surface.
- Don't stack shadows for elevation; use borders and the surface ladder.
- Don't use a non-pill radius for buttons (6–24px is for cards/small controls).

## Accessibility
Text/background pairs must meet **WCAG AA**: black on white is 21:1 (AAA), black on `#f1f1f1` is 18.59:1. Provide alt text for imagery and support text resizing without breaking layout. Every interactive element needs a visible focus state (`{colors.focus-ring}`).

## Responsive Behavior

| Name | Width | Key Changes |
|---|---|---|
| Mobile | < 640px | Single column; nav collapses to a hamburger; card grids stack |
| Tablet | ≥ 640px | Two-column grids; nav still collapsed |
| Desktop | ≥ 768px | Full nav; layouts may reach three columns |
| Desktop Large | ≥ 1024px | Container reaches max-width; whitespace expands |

### Touch Targets
- Interactive elements ≥ 44 × 44px.
- At least 16px between tappable elements.

## Iteration Guide

1. One component at a time; reference its YAML key (`{component.button-primary}`, `{component.composer}`).
2. Variants live as separate entries (`-hover`, `-disabled`, `-dark`).
3. Use `{token.refs}` everywhere — never inline hex.
4. Colour is content-only; the UI stays monochrome.
5. Dark is the native mode; design it first, then invert for light.
6. When in doubt about emphasis: more whitespace before more weight.

## Known Gaps

- OpenAI Sans and SF Mono are proprietary; the app falls back to its bundled humanist sans (`SansUi`) and monospace (`MonoCode`).
- The blue focus ring (`{colors.focus-ring}`) is a web affordance; the app relies on Android's focus/ripple semantics instead.
- This document does not cover ChatGPT's user-selectable **accent colour** (Blue/Green/Yellow/Pink/Orange/Purple and a neutral Black/White) or the web/Windows-only **contrast** setting; only the default monochrome appearance is described.
- Model-specific surfaces (canvas, code interpreter, voice, deep research) are out of scope.

---

## Mapping to this app (ChatAI)

The app ships a two-axis theme system: **配色 (theme family)** × **明暗 (light/dark)**. A theme is a registry entry (`ThemeRegistry`), never an `if` in UI or request code. ChatGPT is registered as `ThemeFamily.CHATGPT`. Unlike Claude, the ChatGPT family also swaps **typography** — its body and display faces are sans.

The Apps SDK **UI guidelines** for partner apps inside ChatGPT reinforce the same rules, and they are baked into this theme:
- Use **system colours for text, icons and dividers**; don't redefine them.
- A brand accent may appear on **primary buttons inside app display modes** — here the "brand" is monochrome black/white, so nothing chromatic enters chrome.
- **Do not colour the backgrounds of text areas.**
- **Inherit the system font stack**; limit size variation.
- Respect **system corner rounds**; icons are **monochromatic and outlined**.
- Maintain **WCAG AA** contrast.

### `ChatColors` slots (`ui/theme/ChatColors.kt`)

| Slot | Light | Dark | Notes |
|---|---|---|---|
| `canvas` | `#ffffff` | `#000000` | 纯白 / 纯黑画布（见「Deliberate deviations」） |
| `surfaceSoft` | `#f1f1f1` | `#171717` | secondary surface / sunken sidebar |
| `surfaceCard` | `#f1f1f1` | `#2f2f2f` | cards + user bubble + composer |
| `surfaceCreamStrong` | `#e5e5e5` | `#383838` | strongest neutral fill (name is Claude-era; value is neutral) |
| `hairline` | `#dddee1` | `#3e3e42` | 1px borders |
| `hairlineSoft` | `#ececec` | `#2f2f2f` | in-band divider |
| `codeBackground` | `#0d0d0d` | `#0d0d0d` | code blocks are dark in both modes |
| `codeOnBackground` | `#ececec` | `#ececec` | |
| `codeHeaderText` | `#8e8e93` | `#8f8f8f` | |
| `codeButtonBackground` | `#2f2f2f` | `#2f2f2f` | copy affordance |
| `bubbleUser` | `#f1f1f1` | `#2f2f2f` | neutral, never accent-tinted |
| `bubbleUserText` | `#000000` | `#ececec` | |
| `chipBackground` | `#f1f1f1` | `#2f2f2f` | |
| `accentTeal` | `#10a37f` | `#10a37f` | used only as a **success tint**, not as chrome |
| `accentAmber` | `#f5a623` | `#f5a623` | semantic |
| `success` | `#10a37f` | `#10a37f` | status only |
| `warning` | `#f5a623` | `#f5a623` | |

### Material3 `ColorScheme` slots (`ui/theme/ChatGptTheme.kt`)

| Slot | Light | Dark |
|---|---|---|
| `primary` / `surfaceTint` / `inversePrimary` | `#000000` / `#000000` / `#333333` | `#ffffff` / `#ffffff` / `#ececec` |
| `onPrimary` | `#ffffff` | `#000000` |
| `primaryContainer` / `onPrimaryContainer` | `#f1f1f1` / `#000000` | `#383838` / `#ececec` |
| `secondary` / `onSecondary` | `#333333` / `#ffffff` | `#b4b4b4` / `#212121` |
| `tertiary` / `onTertiary` | `#f5a623` / `#000000` | `#f5a623` / `#000000` |
| `background` / `surface` / `onBackground` / `onSurface` | `#ffffff` ×2 / `#000000` ×2 | `#000000` ×2 / `#ececec` ×2 |
| `surfaceVariant` / `onSurfaceVariant` | `#f1f1f1` / `#767881` | `#2f2f2f` / `#b4b4b4` |
| `inverseSurface` / `inverseOnSurface` | `#000000` / `#ffffff` | `#ececec` / `#000000` |
| `error` / `onError` / `errorContainer` / `onErrorContainer` | `#ef4146` / `#ffffff` / `#fde7e7` / `#ef4146` | `#e08585` / `#3a1414` / `#4a1f1f` / `#e08585` |
| `outline` / `outlineVariant` | `#dddee1` / `#ececec` | `#3e3e42` / `#3e3e42` |
| `surfaceBright` / `surfaceDim` | `#ffffff` / `#f1f1f1` | `#383838` / `#000000` |
| `surfaceContainerLowest` → `Highest` | `#ffffff` / `#f1f1f1` / `#f1f1f1` / `#e5e5e5` / `#e5e5e5` | `#000000` / `#171717` / `#2f2f2f` / `#2f2f2f` / `#383838` |

### Typography
`AppTheme` carries a Material3 `Typography` and a `ChatTypography`. Claude uses `SerifTypography` + a serif body; **ChatGPT uses `SansTypography` + a sans body** (`ChatTypography.ChatGpt`), so switching the 配色 axis also switches the message/markdown body from serif to sans. Sizes and line heights are shared between the two ladders to avoid layout jumps.

### Deliberate deviations
- **The ChatGPT canvas is pure white / black, not `#ffffff` / `#212121`.** Real ChatGPT uses `#212121` for its dark canvas; the ChatGPT family here uses `#000000` for higher contrast (light stays `#ffffff`). The Claude family is unaffected and keeps its warm cream `#faf9f5` / `#181715` canvas.
- **The brand glyph is not swapped.** `SpikeMark` still renders the Anthropic mark under both families (out of scope: swapping to the OpenAI mark).
- **No accent-colour picker.** The user-selectable accent analysis is out of scope; the ChatGPT family is fixed to monochrome.
