# Chromium-src patches

Reproducible unified-diff patches applied to the **pristine** Chromium checkout
(`.chromium/chromium/src`, a git tree) during `:javafx.web:configureBuild`.

Use a patch here for any edit to an **unmodified upstream Chromium file** (e.g.
a `BUILD.gn` visibility/dep change, a content-layer registration hook). Files
we *add* or *copy-and-modify wholesale* (the jux engine, ported chrome sources,
new GN targets, stubs) are **not** patches — they're copied into the tree by
`configureBuild` (`engine/` → `src/jux/`, `stubs/` → `src/`).

## How they're applied

`ConfigureBuildTask.applyChromiumPatches()` runs, in sorted filename order, for
each `*.patch`:

1. `git apply --reverse --check <patch>` — if it succeeds the patch is **already
   applied**, so it's skipped (idempotent: re-running `configureBuild` is a no-op).
2. otherwise `git apply --3way <patch>` — 3-way so that after a Chromium version
   bump a drifted file produces a **loud conflict / hard failure** instead of a
   silent mis-patch.

## Authoring / updating a patch

1. Edit the file(s) directly in `.chromium/chromium/src/...`.
2. Capture the diff:
   ```
   ./gradlew :javafx.web:genChromiumPatches \
       -Ppatch.file=0001-short-name.patch \
       -Ppatch.paths=content/browser/BUILD.gn,content/public/browser/foo.h
   ```
   (`patch.paths` are src-relative, comma-separated. Naming: `NNNN-name.patch`,
   numeric prefix sets apply order.)
3. Commit the generated `.patch` here. `configureBuild` re-applies it on a clean
   checkout / new machine / version bump.

## Version upgrades

After bumping `chromium.version` + re-seeding, run `configureBuild`. If a patch
no longer applies, the `--3way` apply fails loudly; inspect the new upstream
file, re-edit, and regenerate the patch with `genChromiumPatches`.

## Current patches

_(none yet — the print-preview port adds them in M1+.)_
