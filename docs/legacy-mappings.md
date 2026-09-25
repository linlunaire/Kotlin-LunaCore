# Retiring the separate Minecraft-Mappings repository

The 26.2 projects do not need the old Minecraft-Mappings repository. The
historical build inputs needed to maintain 1.21.1 live here instead, outside
every Transit Core mod source set. Installing Transit Core is **not** required
to run the old 1.21.1 releases.

## What is preserved

`legacy/mappings/1.21.1/` contains 30 Java files and the dashboard's Fabric.js
5.3.0 file, exported unchanged from commit
`59c5ee19517f61f1b00a54c013322dc32592941f`. `.gitignore` is not a build input and
was omitted. Every byte is covered by `legacy/mappings/1.21.1.sha256` and Git
line-ending conversion is disabled for the payload. Attribution and the
original repository's historical license declaration are documented in
[`NOTICE.md`](../legacy/mappings/NOTICE.md).

The MTR `1.21.1-3.3.2` tag already tracks its materialized Java mappings,
including local 1.21.1 compatibility fixes. Its old `setupFiles` nevertheless
downloads and overwrites some of those files on every build. The maintenance
patch removes that redundant download and copies the archived Fabric.js into
the dashboard from `resources/vendor/fabric/`, alongside its upstream license.

The patch deliberately does **not** replace all Java files with the archive:
that would lose the tag's local adaptations, such as networking and registry
fixes. Java files previously overwritten by the download were compared with
the archive, with only package-token expansion and line-ending normalization;
they matched. The existing files are therefore the correct frozen source.

## Rebuild the historical releases without the old repository

Use Git, PowerShell 7 (`pwsh`), and Java 21. Keep the original Gradle 8.14.5,
Architectury 3.4.164, Loom 1.11.458, Minecraft 1.21.1 and NeoForge 21.1.248
toolchain. No Kotlin or Transit Core runtime migration is performed here.

Create these adjacent checkouts:

```text
workspace/
  Transit-Core/
  Minecraft-Transit-Railway-3.x.x/  # tag 1.21.1-3.3.2
  mtr-ante/                        # tag 1.1.1-1.21.1-beta.2
```

From `Transit-Core`, prepare the MTR checkout:

```powershell
pwsh -File tools/prepare-legacy-1.21.1.ps1 -MtrPath ../Minecraft-Transit-Railway-3.x.x
```

The script verifies all 31 hashes, checks the exact MTR baseline commit,
preflights the patch, and refuses to overwrite differing vendor files. Repeating
it on the same prepared checkout is safe. It makes local file changes only;
it does not create commits, move tags, push, or contact Minecraft-Mappings.
Use `-VerifyOnly` without `-MtrPath` to check just the archived payload.

Run the `build` task with MTR's existing Gradle wrapper first, then do the same
in the adjacent ANTE checkout. ANTE's sibling MTR JAR lookup uses this prepared
build and needs no separate mappings download. Commit the prepared MTR files
to your own maintenance branch if you need ongoing 1.21.1 development.

This removes the **Minecraft-Mappings** dependency, not all build networking:
the original Gradle/Minecraft/Maven dependencies and pinned Font Face Observer
download remain. A bare `--offline` build is not promised on an empty cache.

## Original tags and deletion

The original MTR and ANTE tags are immutable and were not rewritten. A direct
checkout of those old tags, or the original ANTE GitHub Actions workflow that
checks out the unpatched MTR tag, still contains the old download path.
Apply the script above before rebuilding; do not claim that an unchanged tag
has become independent by itself.

The old repository can be retired once this archive and migration tool are
published and a fresh prepared checkout is verified. Deleting it is a separate
owner action, not something this tool performs. Existing released game JARs
do not contact the source repository at runtime.

## Validation scope

- SHA-256 validation covers all 31 original payload files.
- Every payload was also checked against the original Git blob ID. Exports
  disable `core.autocrlf` explicitly so Windows archive conversion cannot
  change historical bytes.
- Initial and repeated migration application are checked on an isolated copy
  of the original MTR tag.
- The patched `build.gradle` contains no Minecraft-Mappings download URL.
- The prepared MTR checkout built both Fabric and NeoForge JARs with Java 21
  and Gradle 8.14.5 (23 tasks, 40 seconds). `setupFiles` downloaded only the
  pre-existing Font Face Observer input, not Minecraft-Mappings.
- ANTE's original 1.21.1 checkout then built both loaders against those local
  MTR JARs (25 tasks, 34 seconds), using the populated dependency cache with
  Gradle `--offline`. No ANTE source or build-script change was required.
- Full legacy game runtime testing is separate from source preparation and
  Gradle validation; no saves or installed test-instance JARs are modified.
