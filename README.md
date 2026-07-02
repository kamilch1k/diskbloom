# diskbloom

A fast, open-source disk-space analyzer for Windows — see what's eating your drive, beautifully.

> **Status:** early work in progress. The scan engine works; the GUI is next.

## Why

Windows disk analyzers make you pick two of: fast, good-looking, open-source, maintained.

| Tool | Fast | Pretty | Open-source | Maintained |
|------|:----:|:------:|:-----------:|:----------:|
| WizTree | ✅ | ➖ | ❌ | ✅ |
| WinDirStat | ➖ | ❌ | ✅ | ✅ |
| SquirrelDisk | ❌ | ✅ | ✅ | ❌ |
| DaisyDisk *(macOS only)* | ✅ | ✅ | ❌ | ✅ |
| **diskbloom** | ✅ | ✅ | ✅ | ✅ |

diskbloom is aiming for all four on Windows — WizTree's speed, DaisyDisk's looks, open source. It's early, so the ✅s in that last row are the target, tracked below.

## Roadmap

- [x] Scan engine — zero-dependency recursive walk, size aggregation, largest-first (with a self-check)
- [ ] JavaFX UI — pick a folder, scan off the UI thread, treemap visualization
- [ ] Raw NTFS MFT reading via Win32 FFI for WizTree-class scan speed
- [ ] Hardlink / junction-aware accuracy
- [ ] Packaged installer (jpackage)

## Build & run

Requires JDK 25+. The engine needs no JavaFX; the upcoming UI does, so a JavaFX-bundled build (e.g. [Liberica JDK "Full"](https://bell-sw.com/pages/downloads/)) is recommended.

```sh
# compile
javac -d out src/dev/diskbloom/core/Scanner.java src/dev/diskbloom/Main.java

# scan a folder — prints the 20 biggest entries + timing
java -cp out dev.diskbloom.Main "C:\Users\you\Downloads"

# run the self-check
java -ea -cp out dev.diskbloom.Main --selfcheck
```

## License

[MIT](LICENSE).
