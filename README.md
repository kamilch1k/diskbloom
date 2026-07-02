# diskbloom

A fast, open-source disk-space analyzer for Windows — see what's eating your drive, beautifully.

> **Status:** early work in progress — the scan engine and a JavaFX treemap UI both work today.

![diskbloom showing a squarified treemap of a folder](docs/screenshot.png)

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
- [x] JavaFX UI — pick a folder, scan off the UI thread, squarified treemap with click-to-drill-down and hover details
- [ ] Raw NTFS MFT reading via Win32 FFI for WizTree-class scan speed
- [ ] Hardlink / junction-aware accuracy
- [ ] Packaged installer (jpackage)

## Build & run

Requires JDK 25+ with JavaFX bundled — e.g. [Liberica JDK "Full"](https://bell-sw.com/pages/downloads/). The `--add-modules` flags assume JavaFX is in the runtime image.

### GUI

```sh
javac --add-modules javafx.controls,javafx.swing -d out $(find src -name "*.java")
java  --add-modules javafx.controls,javafx.swing -cp out dev.diskbloom.Launcher
# open straight to a folder:
java  --add-modules javafx.controls,javafx.swing -cp out dev.diskbloom.Launcher "C:\Program Files"
# export a treemap to PNG and exit:
java "-Ddiskbloom.shot=out.png" --add-modules javafx.controls,javafx.swing -cp out dev.diskbloom.Launcher "C:\Program Files"
```

On Windows, `diskbloom.cmd` compiles and launches the GUI for you (and the Desktop shortcut points at it).

### CLI / engine only

```sh
javac -d out src/dev/diskbloom/core/Sizes.java src/dev/diskbloom/core/Scanner.java src/dev/diskbloom/Main.java
java -cp out dev.diskbloom.Main "C:\Users\you\Downloads"   # biggest 20 entries + timing
java -ea -cp out dev.diskbloom.Main --selfcheck            # self-check
```

## License

[MIT](LICENSE).
