# Changelog

All notable changes in this fork relative to
[FliegendeWurst/TriliumDroid](https://github.com/FliegendeWurst/TriliumDroid)
`master`. Based on upstream as of 2026-11-08; versions follow upstream's
`0.105.0-alphaX` scheme.

## Features

### Calendar
- **Unlocked calendar**: all days are selectable (existing days are highlighted
  instead of blocking the rest). Tapping an empty day creates the
  `Year → Month → Day` structure on demand, with proper Trilium labels
  (`#yearNote`, `#monthNote`, `#dateNote`).
- Day/month/year templates (`dayTemplate`, `monthTemplate`, `yearTemplate`
  relations) are applied when creating date notes; falls back to a note titled
  "Day Note Template". Template `iconClass` is inherited in the tree and toolbar.
- Safety guards: calendar root/year/month/date notes cannot be deleted from the
  app (toast instead).

### Share into journal
- Text shared from other Android apps lands as a **child of today's calendar
  note** instead of `#inbox`. Works both when the app is cold-started and when
  it is already running (`onNewIntent`), including HTML content.

### Quick note widget
- Standalone 1×1 home screen widget: one tap opens a fresh note under today's
  journal entry, in the editor, **instantly** (no tree work on the critical path).
- Leaving the editor without typing deletes the empty stub again — safely, by
  actual content check (never by a "modified" flag), and only ever the stub's
  own branch. The deletion is synced so no orphan remains on the server.
- Leaving a quick note never closes the app: you return to what was open before,
  or to the regular home screen (tree + initial note) after a cold start.

### UI & customization
- Customizable **accent color and gradient theme engine**; modernized app styling.
- Note tree bottom bar and navigation columns (Top bar) are configurable in the
  FAB settings; multiple left/right FABs, Journal action.
- Sticky editor toolbar, code note editor, in-note content search.
- Tree action buttons (jump to note, collapse tree, …) with layout fixes.

### Security
- **Biometric app lock** (fingerprint / face / device credential, Android 10+),
  with restricted quick-note sessions: the widget can be used while locked
  without exposing other notes.

### Icons
- **Material Symbols icon pack** (in addition to boxicons) available in the note
  icon picker; icons render in the tree, toolbar, navigation and widgets.
  The icon table is parsed on a background thread at startup.

## Sync & stability

- **TriliumNext v0.107.0 support** (sync version 41, database version 242).
- **Pull chunks are applied atomically** in a single SQLite transaction: the UI
  can never observe a half-applied batch (e.g. a deleted parent note whose
  branch is still alive). The pull cursor only advances after a chunk commits,
  so interrupted chunks are re-pulled instead of lost.
- **Retry and timeout handling**: up to 5 retries with backoff per request,
  HTTP status checking, 90 s read/write timeouts, keep-alive connection pool.
  Rewrote the recursive `runBlocking` pull loop into a structured suspend loop
  with proper cancellation.
- **Self-healing consistency pass** after every completed sync: branches whose
  parent or child note no longer exists are marked deleted and the deletion
  synced (heals rows missed after server-side entity erase or backup restores).
- **Tree cache invalidation is atomic**: a full tree rebuild clears the note
  cache inside its own lock. Previously the cache was cleared after every sync
  without the lock, which raced with in-flight rebuilds and produced a tree
  collapsed to a single item on large databases.
- **Rebuild retry**: if the branch walk aborts part-way (e.g. a CursorWindow
  failure while another thread writes), the full rebuild is retried once
  instead of silently leaving a partial tree.
- Null-safety hardening: paths through deleted notes no longer crash
  (`getNotePaths`, note paths widget, `getTreeListInternal`).
- Database is no longer closed on configuration changes (screen rotation).
- Startup performance: the Material icon table is parsed off the main thread;
  push-only syncs no longer rebuild the note tree.
- At most one full tree rebuild per editing session: saving a note marks the
  tree as pending a rebuild instead of rebuilding immediately, and the
  auto-sync completion performs it (previously both rebuilt the tree, ~1.7 s
  each on large databases). Applies to quick notes and regular edits alike.
- Fixed a leaked ZipFile on every stylesheet request from the note editor.
- Fixes for editor race conditions (note view vs. editor), sync timing after
  edits, and the note tree rendering (padding, icon offset, fonts).

## Building

See the README for the Docker-based build. The debug APK installs alongside the
original app (package id suffix `.debug`).
