# Plan: Make croc-app actually discover peers on Android

## Target (user chose: only croc-app)
Fix `../croc-app/croc-mobile/go.mod` (module `com.dking.crocapp.croc` — the
gomobile main module that builds croc into the Android `.aar`). This is outside
the current workspace root (`/home/koka/src/abakCroc/croc`) but the files are
editable.

**Not touched:** `croc/go.mod` (this repo). It has zero effect on croc-app
because croc is a dependency/submodule and Go ignores `replace` directives from
dependencies — only the main module's replaces count. croc-app already proves
this: it overrides `peerdiscovery` itself in `croc-mobile/go.mod`.

## Root cause (verified from source)
`croc-mobile/go.mod` pins peerdiscovery to the Dec-2025 fork
`abakum/peerdiscovery bda39395085f`. That version calls `net.Interfaces()` and
`golang.org/x/net/ipv4|ipv6` directly — **no anet at all**. On Android,
`net.Interfaces()` returns nothing, so `croc --local` (invoked via
`cli.Run()` from Kotlin, see `croc-mobile/croc.go`) finds **no peers**.

crocson already fixed this by using the Jun-2026 fork
`abakum/peerdiscovery 7a998a1dc036`, which was switched to `wlynxg/anet`
(`anet.Interfaces()` etc.) — the Android-compatible enumeration. That newer fork
also needs the `anet` replace to link under Go 1.25 (see next).

## Why the `anet` replace is mandatory (not optional)
`croc-mobile/go.mod` declares `go 1.25.0`. The newer peerdiscovery fork requires
`github.com/wlynxg/anet v0.0.5`, which uses `//go:linkname` to internal symbols
`net.zoneCache` and `golang.org/x/net/internal/socket.zoneCache`. Since Go 1.23
the linker rejects those (needs `-checklinkname=0`), so the original
`wlynxg/anet` **does not link under Go 1.25+**. The fork `abakum/anet`
removed those directives for exactly this reason. Hence the `anet` replace is
required, mirroring `crocson/go.mod`.

## Change — `croc-app/croc-mobile/go.mod`

Edit the `replace (...)` block at the bottom:

```diff
 replace (
 	github.com/schollz/croc/v10 => ../third_party/croc-src
-	github.com/schollz/peerdiscovery => github.com/abakum/peerdiscovery v0.0.0-20251222054903-bda39395085f
+	github.com/schollz/peerdiscovery => github.com/abakum/peerdiscovery v0.0.0-20260614170419-7a998a1dc036
+	github.com/wlynxg/anet => github.com/abakum/anet v0.0.0-20260611221740-26109fc88d23
 )
```

Versions match `../../crocson/go.mod` exactly (known-good, already in module
cache). The `require github.com/schollz/peerdiscovery v1.7.6 // indirect` line
stays as-is — Go uses the replace target's code; the version is kept for MVS.

## Then refresh the module graph
```bash
cd ../croc-app/croc-mobile
go mod tidy
```
This will:
- add `github.com/wlynxg/anet v0.0.5 // indirect` to `require` (now pulled
  transitively by the newer peerdiscovery);
- update `go.sum` with hashes for `abakum/peerdiscovery 7a998a1dc036`,
  `wlynxg/anet`, and `abakum/anet`;
- remove the now-unused `bda39395085f` entry from `go.sum`.

## Verify
```bash
cd ../croc-app/croc-mobile
go list -m github.com/schollz/peerdiscovery github.com/wlynxg/anet
# Expected:
#   github.com/schollz/peerdiscovery v0.0.0-20260614170419-7a998a1dc036 => github.com/abakum/peerdiscovery v0.0.0-20260614170419-7a998a1dc036
#   github.com/wlynxg/anet v0.0.5 => github.com/abakum/anet v0.0.0-20260611221740-26109fc88d23
go build ./...
go vet ./...
```
A full proof requires building the APK with gomobile for an Android target and
confirming `--local` discovers peers on a device — that's a runtime check, not
possible from here.

## Notes
- `croc-app` also has a `.gitmodules` entry pointing `third_party/croc-src` at
  `github.com/abakCroc/croc` (this repo). No submodule bump is needed for this
  fix — the change is purely in croc-mobile's module resolution.
- croc-app's `croc-mobile/go.mod` and crocson will now use the **same**
  peerdiscovery/anet versions → consistent Android behavior across both apps.

## Files touched
- `../croc-app/croc-mobile/go.mod` (2 lines in the `replace` block)
- `../croc-app/croc-mobile/go.sum` (via `go mod tidy`)
