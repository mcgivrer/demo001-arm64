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

## Technical documentation

All technical documentation lives in `src/docs/`, one Markdown file per chapter (class or concept). **This documentation must be kept up to date with every code change** — if a class is modified, its chapter must be updated in the same work session.

### Chapter map

| File | Class / Concept |
|------|----------------|
| `01-architecture.md` | Overall architecture, class diagram, application lifecycle |
| `02-entity-behavior.md` | Entity / Behavior composition pattern |
| `03-particle-system.md` | ParticleSystem |
| `04-spectral-classification.md` | Harvard spectral classification, star colours |
| `05-rotations-3d.md` | 3D rotations (Yaw/Pitch/Roll), Brownian drift |
| `06-perspective-projection.md` | Perspective projection, inverse-square brightness, glow |
| `07-game-loop.md` | Swing game loop, EDT, delta-time |
| `08-input-controls.md` | InputState, GamePanel listeners, keyboard/mouse camera control |
| `09-thrust-engine.md` | Engine power (thrust) throttle, speed/power HUD |

### Documentation rules

Each chapter must contain:
- **Explanatory prose** — describe the concept, its motivation, and its role in the project.
- **Mermaid diagrams** — class diagrams, sequence diagrams, flowcharts (fenced ` ```mermaid ` blocks).
- **PlantUML diagrams** — detailed UML (fenced ` ```plantuml ` blocks with `@startuml`/`@enduml`).
- **MathML notation** — for all mathematical formulas and physical laws (inline `<math>` blocks or `$$` LaTeX-style for renderers that support it).
- **SVG illustrations** — stored in `src/docs/illustrations/` and referenced with a relative path `![label](illustrations/file.svg)`.

### Adding a new class or concept

1. Create `src/docs/NN-<slug>.md` following the chapter structure above.
2. Add any SVG illustration to `src/docs/illustrations/`.
3. Add the new chapter to the table above in this file.
