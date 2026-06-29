# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build system

This project uses a custom `build.sh` script instead of Maven or Gradle. Java 26 (Zulu) is required — managed via sdkman (`.sdkmanrc`).

```bash
./build.sh          # compile sources → target/classes/
./build.sh build    # same as above
./build.sh jar      # compile + package → target/demo001.jar
./build.sh run      # run via JAR if present, otherwise via target/classes/
./build.sh run arg1 arg2  # pass arguments to the application
./build.sh clean    # remove target/
./build.sh all      # build then run
```

## Structure

- `src/main/java/` — Java sources (no package; `Main` is the top-level class)
- `src/main/resources/` — classpath resources copied into `target/classes/` at build time
  - `config.properties` — window size (`app.window.width/height`) and language (`app.language.default`: EN/FR/DE/ES/IT)
  - `i18n/messages*.properties` — i18n bundles for the window title (base = English, suffixes `_fr`, `_de`, `_es`, `_it`)
- `target/classes/` — compiled `.class` files + copied resources (gitignored)
- `target/demo001.jar` — executable JAR (gitignored); name derives from the project directory name

## Application entry point

`Main.java` follows a constructor-init / `run(args)` lifecycle. The constructor loads `config.properties` and the `ResourceBundle`, resolving the locale from `app.language.default`. `run()` prints CLI args then dispatches window creation to the Swing EDT via `SwingUtilities.invokeLater`. The JVM stays alive through the EDT (non-daemon thread); `JFrame.EXIT_ON_CLOSE` handles shutdown.
