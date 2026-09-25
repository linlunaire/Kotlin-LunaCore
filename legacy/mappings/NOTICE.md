# Historical Minecraft Mappings snapshot

`1.21.1/` contains the 30 Java sources and `fabric.min.js` from Git commit
`59c5ee19517f61f1b00a54c013322dc32592941f`, without byte changes. The original
`.gitignore` was deliberately omitted. `1.21.1.sha256` identifies every payload.
These are historical build inputs, not source sets or resources of the 26.2 mod.

## Java source provenance

- Original project: Jonathan Ho's `Minecraft-Mappings`, later forked by Linkode
  (`Jhesterccj`) and maintained under `linlunaire/Minecraft-Mappings`.
- Archived commit: Linkode, 2026-08-22, "Try updating to version 1.21.1".
- The archived commit contains no `LICENSE` file. This archive does not pretend
  that one was present and does not relicense the contributors' work.
- The original repository's ancestor
  [`e7b4ea64e2c66c6f1d0bce5e956d1710cf52e362/README.md`](https://github.com/jonafanho/Minecraft-Mappings/blob/e7b4ea64e2c66c6f1d0bce5e956d1710cf52e362/README.md)
  explicitly states that the project is MIT-licensed. That README was removed
  when the source layout changed at `a7945dbb18d88ecb58bf4a7920f1104fd7bbfc74`.
  The same license statement is preserved in the MTR
  [`1.21.1-3.3.2` source snapshot](https://github.com/linlunaire/Minecraft-Transit-Railway/blob/52095c771f8ab36527a723bb922fc6d8650bd4b5/common/src/main/java/mtr/mappings/README.md).
- The original 2021 declaration is also retained locally as
  [`UPSTREAM-README-2021.md`](UPSTREAM-README-2021.md), so the evidence does not
  depend on continued availability of the old repository.
- Credit remains with Jonathan Ho, Linkode and the original contributors.
  The MIT terms are reproduced in `LICENSE-MIT.txt`; this notice records the
  licensing evidence separately from the unchanged payload.

## Fabric.js

`fabric.min.js` identifies itself as Fabric.js 5.3.0. It is the dashboard's
browser canvas library, unrelated to the Fabric Minecraft mod loader.
Its upstream [v5.3.0 license](https://github.com/fabricjs/fabric.js/blob/v5.3.0/LICENSE)
is reproduced in `LICENSE-FABRIC.txt`, crediting Printio (Juriy Zaytsev and
Maxim Chernyak). No re-minification or header insertion was performed.
