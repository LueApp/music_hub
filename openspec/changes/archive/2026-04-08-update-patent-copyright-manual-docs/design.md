## Context

Three documentation files under `docs/专利软著/` were written in March 2025 and have not been updated since. The app has gained significant features (discover/browse, platform auth, playlist sync, Bilibili medialist, smart timeout, mini ball double-click, QQ dialog dismissal, fast scrollbar, import from library) and the codebase grew from ~8,900 to ~14,375 lines of Kotlin.

## Goals / Non-Goals

**Goals:**
- Update patent disclosure to cover new technical innovations added since initial filing
- Update copyright registration form with accurate line counts and feature descriptions
- Update user manual with sections for all new user-facing features

**Non-Goals:**
- Regenerating code excerpt files (3.代码前1500行.txt / 4.代码后1500行.txt)
- Regenerating architecture diagrams (.png files)
- Converting to .docx (done separately)
- Changing core patent claims — only adding supplementary material

## Decisions

**1. Additive updates to patent disclosure**
- Add new key points (关键点7-10) for discover/browse, playlist sync, smart timeout, and QQ dialog dismissal
- Update system architecture table to include new layers (auth, sync, discover)
- Update service layer description to include new services
- Rationale: Adding supplementary innovations strengthens the patent without altering existing claims

**2. User manual structured as new chapters**
- Add new numbered sections (12-16) for discover, platform login, playlist sync, mini ball navigation, import from library
- Update existing sections where features have expanded (e.g., Bilibili in platform filters, fast scrollbar in library)
- Rationale: Preserves existing manual structure while making new features discoverable

**3. Read current source code for accuracy**
- Before writing doc updates, read relevant source files to ensure descriptions match actual implementation
- Rationale: Docs must accurately reflect shipped behavior, not design intent

## Risks / Trade-offs

- [Patent language drift] → Keep new sections stylistically consistent with existing patent prose
- [Feature description accuracy] → Verify each feature against source code before documenting
