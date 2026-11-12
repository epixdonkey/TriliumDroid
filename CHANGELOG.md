# Changelog

All notable changes in this fork relative to
[FliegendeWurst/TriliumDroid](https://github.com/FliegendeWurst/TriliumDroid)
`master`. Based on upstream as of 2026-11-08; versions follow upstream's
`0.105.0-alphaX` scheme.

## Features

### Sync status dot
- A small **colored dot in the toolbar** shows the sync state at a glance:
  green = all changes are on the server, yellow = local changes are waiting to
  be pushed, red = the last sync attempt failed (changes are not on the
  server yet), grey = sync is not configured. Tapping it shows the details.

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
- **Images and screenshots can be shared the same way**: sharing any `image/*`
  from the Android share sheet creates a note (titled with the current time)
  under today's calendar note with the picture embedded as an **image
  attachment** — exactly how the desktop frontend stores pasted images — so it
  renders on every client and can be captioned or edited like any text note.
  Oversized images (>16 MB) and unreadable streams are rejected with a toast.

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

### Note map
- **The note map opens around the current note**: the note itself, everything
  related to it, and one level further out (bounded to 400 nodes) — readable
  and useful on a phone, unlike a full-vault graph.
- The Specials tab now bundles the "technicals" of the current note: its map,
  the whole-vault map, and the note metadata drawer.
- A "Building the map…" placeholder is shown while the graph is constructed
  in the background.
- Fixed navigation silently doing nothing in some states: fragment
  transactions were dropped when the fragment manager state was saved
  (`commitAllowingStateLoss`).
- Relations pointing to deleted notes are now reported as one summary line
  instead of one warning per relation (large vaults produced thousands of
  them).

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
- **Pushes wait for server acknowledgement**: the sync push cursor is now
  only advanced after the server confirms the request. Previously the push
  was fire-and-forget: the cursor advanced immediately, so a request that
  failed in flight (app backgrounded, network drop) silently skipped those
  changes forever — the notes existed only on the phone while sync reported
  OK. On first launch of this version the cursor is rolled back once and the
  most recent changes re-sent, recovering any notes lost this way (the
  server skips changes it already has).
- Fixed a leaked ZipFile on every stylesheet request from the note editor.
- Fixed the widget configuration dialog opening (and leaking) on launch: any
  intent carrying other extras but no `appWidgetId` was misread as a widget
  launch (`Bundle.getInt` returns 0 for missing keys).
- **Note map no longer freezes the app on large databases** (the knowledge
  graph used to ANR on ~20 000 notes): graph construction and the force
  simulation now run on background threads, the O(n²) charge pass is skipped
  above 400 nodes, labels and edges are omitted above 1000 (plain dots
  instead), and repaints are throttled so the UI stays responsive.
- The database is no longer closed while a sync or background push is still
  running (e.g. the push started by sharing into a cold-started app).
- Sharing into a cold-started app no longer rebuilds the note tree it will
  never display — the share path got roughly two seconds faster.
- Quieted log noise: the blob existence probe during note creation, dangling
  branches (cleaned up after sync), and deferred tree scrolls now log at
  informational level instead of warnings/errors.
- Cold start performs at most two full tree rebuilds (previously up to three:
  a late resume no longer triggers a redundant rebuild while one is already
  running).
- Sharing text into the app performs one tree rebuild instead of two,
  halving the blocking time of the cold-start share path.
- Fixes for editor race conditions (note view vs. editor), sync timing after
  edits, and the note tree rendering (padding, icon offset, fonts).

## Building

See the README for the Docker-based build. The debug APK installs alongside the
original app (package id suffix `.debug`).
