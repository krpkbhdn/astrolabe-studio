<div align="center">

<img src="packaging/icon.png" alt="Claude Code Manager" width="96" />

# Claude Code Manager

**One desktop app to run, watch and steer many local Claude Code sessions.**

Visual review of what Claude builds (screenshots and live websites), agent teams, notifications, and one-click approval of everything Claude asks for.

[![Release](https://img.shields.io/github/v/release/krpkbhdn/cc-manager?label=release)](https://github.com/krpkbhdn/cc-manager/releases)
![Windows](https://img.shields.io/badge/Windows-installer-informational)
![Linux and macOS](https://img.shields.io/badge/Linux%20%7C%20macOS-build%20from%20source-lightgrey)
![Java](https://img.shields.io/badge/Java-25%20(JBR)-orange)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/krpkbhdn)

**English** · [Українська](README.uk.md)

</div>

![Overview](docs/images/overview.png)

**Contents:** [Why](#why) · [Visual review](#visual-review) · [Web review](#web-review) · [Quick start](#quick-start) · [Features](#features) · [Screenshots](#screenshots) · [Requirements](#requirements) · [Install](#install) · [Build from source](#build-and-run-from-source) · [Shortcuts](#keyboard-shortcuts) · [Configuration](#configuration) · [Support](#support-the-project) · [License](#license)

## Why

Working with several Claude Code sessions means a pile of terminals, and you never know which one is waiting for you.
Claude Code Manager puts them in one window: every session shows its status, and every question, plan or permission request
becomes a dialog you can answer without hunting for the right terminal.

The feature no other Claude Code tool has is **Visual review**: when Claude changes a UI, you mark up its screenshots and send the marks straight back.

Everything runs **locally on your own Claude Code installation**. There is no server component and nothing proxies your sessions.

## Visual review

Claude changes a UI, takes screenshots and hands them to you. You circle what is wrong, write a comment on each mark, and
the annotated images go back into the session. No more describing "the third button on the left" in words.

![Visual review: marks and comments on a screenshot](docs/images/review.png)

<details>
<summary>Light theme</summary>

![Visual review, light theme](docs/images/review-light.png)

</details>

**How it works**

1. Claude calls the MCP tool `submit_screenshots` with the images it made. The first call asks your permission, because the app copies the given files.
2. The **Review** tab (`Ctrl+Shift+Z`) shows them, with a badge counting reviews that wait for you. It never opens by itself.
3. You mark problems with **Rectangle**, **Ellipse**, **Pin** or **Freeform** (a polygon placed point by point, closed by a click on the first point). Marks can be moved, resized by 8 handles, and polygon vertices dragged. Marks are numbered on every image and each has its own comment.
4. **Send** hands the annotated images plus a `review.md` with all comments to the bound or a chosen session through its prompt queue, or starts a new session. **Approve** closes the review.

Reviews older than 7 days are deleted automatically (`cc-manager.review.retention`).

Bonus: the bundled plugin skill `visual-review` teaches Claude to take and submit screenshots on its own.

## Web review

Claude builds a website; you review the running page itself, not just screenshots. The **Web** tab (`Alt+F11`) embeds Chromium (JCEF), so you click the real page and tell Claude exactly what to change.

![Web tab: inspector, pins and device mode](docs/images/web-review.png)

- **Pick elements** (`I`) and leave a comment: a numbered pin is anchored to the element (selector, XPath, computed styles, source hint).
- **Edit styles live** in the inspector and see the result at once; the changes go into the feedback.
- **Device mode**: mobile, tablet and desktop presets or a custom size, rotation and zoom up to 400%; feedback is scoped per device category.
- **Overlays**: spacing, audit, similar elements and contrast, plus a layer stack under the cursor (right-click).
- **Screenshots to Review**: the visible part, the whole page or a dragged region go to the Review tab (`S`, `Shift+S`, `R`).
- **Send** puts the open items into the bound or a chosen session as a prompt (through its queue), or starts a new one; **Approve** settles the revision.

The Web tab needs the JCEF flavour of JetBrains Runtime (`jbrsdk_jcef`); the build checks it, and without it the tab explains what is missing.

**Review or Web?** Use [Visual review](#visual-review) for images Claude produced (screenshots, mockups); use Web review for a page that is running right now.

## Quick start

1. Install and log in to [Claude Code](https://code.claude.com/docs/en/overview).
2. Install Claude Code Manager (Windows: the `.msi` from [Releases](https://github.com/krpkbhdn/cc-manager/releases); Linux and macOS: [build the installer](#build-and-run-from-source)).
3. Start the app, press `Ctrl+Shift+O` to add a project folder, then `Ctrl+Shift+T` to start a Claude session in it.

When any session needs you, it turns red on the Overview and in the status bar; press `Ctrl+Shift+N` to answer the next one.

## Features

**Visual review**
- **[Web review](#web-review)**: an embedded Chromium tab for a running website: pick elements, pin comments, edit styles live, device presets, audit and spacing overlays, screenshots to Review, feedback sent to the session as a prompt.
- **[Visual review](#visual-review)**: Claude submits screenshots, you mark problem areas (rectangle, ellipse, pin, freeform polygon) with numbered comments in the Review tab, then send the annotated images and `review.md` back to a session or approve.

**Sessions**
- **Projects and sessions**: register project folders, start or resume Claude sessions in a real embedded terminal.
- **Live status**: every session is *idle*, *working* or *needs action*; the status bar, notifications and the "Sessions" screen show it at a glance.
- **Answer from the app**: questions, plan approvals, tool permissions and MCP forms open as modal dialogs. The terminal dialog stays live, and whichever answers first wins.
- **Plain shells**: PowerShell, cmd, Git Bash and WSL on Windows, your `$SHELL` elsewhere, as a tab next to Claude.

**Agents and automation**
- **Agent teams**: define roles (model, effort, tools) and start a lead that delegates to native subagents.
- **Assistant**: a Claude session outside any project that opens projects, starts sessions and drives the app through a dedicated MCP server.
- **Prompt library and queue**: reusable templates with placeholders, and a per-session queue that types the next prompt when a turn ends.
- **Run configurations**: start your app from the manager; Claude can register one for its own git worktree.

**Workspace**
- **Git tab**: changes with hunk-aware diff, commit, log graph, branches, stashes and tags.
- **Usage meters**: 5-hour and 7-day limits (Pro/Max plans only; Team plans get the per-session list instead) plus per-session model, context and cost.
- **Extensions**: manage plugins, marketplaces, MCP servers and skills.
- **Voice dictation** (experimental, Windows first): hold Space in the prompt, task and commit-message fields to dictate with a local Whisper model. Models are downloaded only when you ask.
- **Language servers (LSP)**: quick setup per project. The app detects the languages in your folder, installs the server (jdtls and kotlin-lsp are downloaded and checksum-verified automatically) and enables Claude's LSP plugin with one switch in Project settings → Languages. Supported: TypeScript/JavaScript (incl. React), CSS/SCSS/Less, C/C++, Java, Python, Rust, Go, C# and Kotlin.
- **Code Mode for MCP**: hide a project's chosen MCP servers from Claude and let it find tools by search and call them from a short JavaScript snippet instead, so large tool lists stop filling the context. Turn it on per project in Project settings → Code Mode, or ship it in a project preset. Isolation levels: a **GraalJS sandbox** inside the app (default, no extra software) or a **Docker/Podman container** (strongest boundary, choose it in Settings → Code Mode). Calls to read-only tools run on their own; everything else asks you first.
- **Command palette and shortcuts** for almost everything.

## Screenshots

![Answering a request from the app](docs/images/answer.png)
*Questions, plans and permissions open as dialogs, no need to find the terminal.*

| | |
|---|---|
| ![Sessions](docs/images/sessions.png)<br/>**Sessions** | ![Git changes](docs/images/git-changes.png)<br/>**Git: changes** |
| ![Git log](docs/images/git-log.png)<br/>**Git: log** | ![Teams](docs/images/teams.png)<br/>**Agent teams** |
| ![Usage](docs/images/usage.png)<br/>**Usage** | ![Notifications](docs/images/notifications.png)<br/>**Notifications** |

<details>
<summary>Light theme</summary>

![Overview, light theme](docs/images/overview-light.png)

</details>

> The screenshots are rendered from the built-in preview with fake data.

## Requirements

To use the app:

- [Claude Code](https://code.claude.com/docs/en/overview) (the native `claude` binary), installed and logged in.
- `git` on `PATH` for the Git tab.

To build from source:

- A JDK to start Gradle (17 or newer). The build downloads **JetBrains Runtime 25** itself through the Foojay resolver
  (about 670 MB, once). Nothing else to install.
- Packaging must run on the target OS: an installer for Windows is built on Windows, `.deb` on Linux, `.dmg` on macOS.

> Windows is the most tested platform. Linux and macOS builds are supported by the build but have had less testing.

## Install

### Windows

Download the `.msi` from the [Releases](https://github.com/krpkbhdn/cc-manager/releases) page, check it against the
published `.sha256` (`(Get-FileHash .\<installer>.msi).Hash`), and run it. It installs per user with a Start menu entry and a desktop shortcut.

> The installer is not code-signed, so Windows SmartScreen may warn. Choose *More info*, then *Run anyway*, after checking the hash.

### Linux

Build the `.deb` yourself (see below) and install it:

```sh
sudo apt install ./build/compose/binaries/main/deb/*.deb
```

### macOS

Build the `.dmg` yourself (see below), open it and drag the app to Applications.

## Build and run from source

```sh
git clone https://github.com/krpkbhdn/cc-manager.git
cd cc-manager
```

| Task | Windows (PowerShell) | Linux / macOS / Git Bash |
|---|---|---|
| Run the app | `.\gradlew run` | `./gradlew run` |
| Tests | `.\gradlew test` | `./gradlew test` |
| Compile, test, jar | `.\gradlew build` | `./gradlew build` |
| Installer | `.\gradlew packageMsi` (or `packageExe`) | `./gradlew packageDeb` (Linux), `./gradlew packageDmg` (macOS) |

Installers are written to `build/compose/binaries/main/<format>/`.

The first build needs internet: it downloads Gradle and JBR 25, so it takes a while. Without JBR the app falls back to the plain OS window frame; `-Dccm.nativeFrame=true` forces the native OS frame even on JBR.

## Keyboard shortcuts

<details>
<summary>Show all shortcuts</summary>

The table shows Windows and Linux chords. On macOS most of them use `Cmd` or `Cmd+Shift` instead (`Cmd+T`, `Cmd+O`, `Cmd+K`, `Cmd+1`..`9`, `Cmd+Shift+S/I/E/N/G/K/U/R/Y/Z/M`); `Alt+F12` is unchanged.

| Action | Shortcut |
|---|---|
| Command palette | `Ctrl+Shift+P` |
| Shortcut sheet | `F1` or `Ctrl+Shift+/` |
| Add project | `Ctrl+Shift+O` |
| New Claude session | `Ctrl+Shift+T` |
| Sessions | `Ctrl+Shift+S` |
| Answer next waiting session | `Ctrl+Shift+N` |
| Assistant / ask the assistant | `Ctrl+Shift+I` / `Ctrl+Shift+E` |
| Git tab / commit / push | `Ctrl+Shift+G` / `Ctrl+Shift+K` / `Ctrl+Shift+U` |
| Run selected configuration | `Ctrl+Shift+R` |
| Review | `Ctrl+Shift+Z` |
| Usage | `Ctrl+Shift+Y` |
| Notifications | `Ctrl+Shift+M` |
| Open terminal | `Alt+F12` |
| Project 1-9 / Overview | `Alt+1`..`Alt+9` / `Alt+0` |

Plain `Ctrl+letter` chords go to the terminal, which is why the app uses `Ctrl+Shift`.

</details>

## Configuration

Data lives in `~/.cc-manager` (SQLite database `cc-manager.db`); change the location with the `cc-manager.data-dir` property (or the `CC_MANAGER_DATA_DIR` environment variable).
`./gradlew run` from a git worktree uses `<tmp>/cc-manager-worktrees/<worktree folder>` instead, unless `CC_MANAGER_DATA_DIR` is set.
Settings are `cc-manager.*` properties, see [`application.properties`](src/main/resources/application.properties).
The hooks and protocol reference is in [docs/claude-code-protocol.md](docs/claude-code-protocol.md).

## License

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE).

Copyright 2026 Bohdan Krupko

## Support the project

Claude Code Manager is free and built in spare time. If it saves you time and you want to support the project and its author, you can buy me a coffee. Thank you!

[![Support me on Ko-fi](https://storage.ko-fi.com/cdn/kofi3.png?v=3)](https://ko-fi.com/krpkbhdn)
