## Why

The three documentation files under `docs/专利软著/` (patent disclosure, copyright registration form, and user manual) were written in March 2025 when the app had ~8,900 lines of code and fewer features. Since then, significant features have been added (discover/browse, platform authentication, Bilibili medialist sync, periodic playlist sync, smart playback timeout, double-click mini ball navigation, QQ Music error dialog dismissal, fast scrollbar, import from library) and the codebase has grown to ~14,375 lines. These docs need to be updated to accurately reflect the current state of the software.

## What Changes

- **Patent disclosure** (`1.专利技术交底书.md`): Add new technical innovations as additional claims/key points — discover/browse with authenticated APIs, playlist sync engine, smart playback timeout, accessibility-based QQ Music dialog dismissal, Bilibili medialist support
- **Copyright registration** (`2.软件著作权采集表.md`): Update source code line count (~8,900 → ~14,375), update main features list and technical characteristics to cover new capabilities
- **User manual** (`5.用户手册.md`): Add sections for discover/browse feature, platform login, playlist sync management, Bilibili content in platform filters, double-click mini ball navigation, import from library

## Non-goals

- Not regenerating the code excerpt files (`3.代码前1500行.txt`, `4.代码后1500行.txt`) — those can be regenerated separately with existing scripts
- Not regenerating architecture diagrams (`.png` files)
- Not updating the `.docx` files (those are generated from the `.md` sources)
- Not changing the patent's core claims — only adding supplementary innovations

## Capabilities

### New Capabilities

_None — this is a documentation-only change, no new code capabilities._

### Modified Capabilities

_None — no spec-level behavior changes._

## Impact

- Files modified: `docs/专利软著/1.专利技术交底书.md`, `docs/专利软著/2.软件著作权采集表.md`, `docs/专利软著/5.用户手册.md`
- No code changes, no API changes, no dependency changes
