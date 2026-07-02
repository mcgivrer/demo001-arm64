# Chapitre 1 — Architecture générale

## Vue d'ensemble

**demo001** est une application de bureau Java qui anime un champ d'étoiles 3D en vol
continu, rendue en **OpenGL ES 3.0** via LWJGL 3 (fenêtre GLFW, shaders GLSL — voir
[chapitre 12](12-opengl-pipeline.md)). Elle est compilée avec Java 26 et packagée via
un script `build.sh` maison qui télécharge les jars LWJGL dans `lib/`.

L'architecture repose sur quatre couches :

1. **Infrastructure applicative** (`Main`, `GLWindow`) — chargement de la configuration,
   localisation, fenêtre GLFW + contexte GL et boucle de jeu.
2. **Infrastructure de rendu** (`RenderContext`, `ShaderProgram`, `QuadRenderer`,
   `TextRenderer`) — shaders partagés et primitives HUD.
3. **Modèle de scène** (`Entity`, `Behavior`) — graphe d'objets génériques avec composition
   de comportements.
4. **Comportements métier** (`ParticleSystem`, `StarfieldBehavior`,
   `NebulaFieldBehavior`) — simulation physique et rendu.

![Architecture overview](illustrations/architecture-overview.svg)

---

## Diagramme de classes

```mermaid
classDiagram
    class Main {
        -int windowWidth
        -int windowHeight
        -String windowTitle
        -List~Entity~ entities
        -long lastTime
        +main(String[] args)
        +run(String[] args)
        -initEntities()
        -runRenderLoop()
        -drawExitOverlay(RenderContext)
        -loadConfig() Properties
    }

    class GLWindow {
        -long handle
        -boolean confirmQuit
        +pollEvents()
        +swapBuffers()
        +shouldClose() boolean
    }

    class RenderContext {
        +ShaderProgram starShader, nebulaShader, quadShader, textShader, blitShader
        +QuadRenderer quads
        +TextRenderer text
    }

    class Entity {
        +double x, y
        +double width, height
        +double dx, dy
        -List~Behavior~ behaviors
        +addBehavior(Behavior)
        +init(RenderContext)
        +update(double dt)
        +draw(RenderContext)
    }

    class Behavior {
        <<interface>>
        +init(RenderContext)
        +update(Entity, double dt)
        +draw(Entity, RenderContext)
    }

    class ParticleSystem {
        +ParticleSystem(int width, int height, InputState input, long seed)
    }

    class CameraState {
        -double velYaw, velPitch, velRoll
        +double cosYaw, sinYaw, cosPitch, sinPitch, cosRoll, sinRoll
        +double enginePower
        +update(double dt)
        +travelFactor() double
    }

    class StarfieldBehavior {
        -double[] sx, sy, sz
        -double[] travelSpeed
        -int[] spectralIdx
        -float[] brightness, baseSize
        -String[] starName
        -long seed
        -long spawnCounter
        -int vao, vbo
        +update(Entity, double dt)
        +draw(Entity, RenderContext)
        -initStar(int i, boolean scatter)
        -subSeed(long seed, long n)$ long
    }

    class NebulaFieldBehavior {
        -double[] zcx, zcy, zcz
        -double[] px, py, pz
        -float[] puffData
        -int vao, fbo, fboTexture, noiseTexture
        +update(Entity, double dt)
        +draw(Entity, RenderContext)
        -initZone(int k, boolean scatter)
    }

    class StarNameGenerator {
        +generate(Random rng)$ String
    }

    Main --> GLWindow : crée
    Main --> RenderContext : crée
    Main "1" --> "*" Entity : possède et dessine
    GLWindow --> InputState : callbacks GLFW
    Entity "1" --> "*" Behavior : délègue
    ParticleSystem --|> Entity : étend
    StarfieldBehavior ..|> Behavior : implémente
    NebulaFieldBehavior ..|> Behavior : implémente
    ParticleSystem --> CameraState : intègre 1×/frame
    ParticleSystem --> StarfieldBehavior : instancie
    ParticleSystem --> NebulaFieldBehavior : instancie (arrière-plan)
    StarfieldBehavior --> CameraState : lit cos/sin + enginePower
    NebulaFieldBehavior --> CameraState : lit cos/sin + travelFactor
    StarfieldBehavior --> StarNameGenerator : nomme les étoiles
    Behavior ..> RenderContext : draw(ctx)
```

---

## Cycle de vie de l'application

```plantuml
@startuml
title Cycle de vie — demo001

participant "JVM / main thread" as JVM
participant "Main" as M
participant "GLWindow / GLFW" as W
participant "RenderContext" as C
participant "Entity / Behavior" as E

JVM -> M : main(args)
activate M
M -> M : new Main()\nloadConfig()\nloadBundle(locale)
M -> M : run(args)\ninitEntities()
M -> W : new GLWindow()\ncontexte OpenGL ES 3.0 + vsync
M -> C : new RenderContext()\ncompile les 5 shaders
M -> E : entity.init(ctx)\nVAO / VBO / FBO

loop tant que !window.shouldClose()
    M -> W : pollEvents() → InputState
    M -> M : Δt (nanoTime)
    M -> E : entity.update(dt)
    M -> M : glClear
    M -> E : entity.draw(ctx)
    M -> M : overlay ESC éventuel
    M -> W : swapBuffers() — vsync
end

M -> W : destroy()\nglfwTerminate
M -> JVM : retour de main → exit
@enduml
```

---

## Flux de données par frame

```mermaid
flowchart LR
    A([vsync ≈16 ms]) --> B[pollEvents\nGLFW → InputState]
    B --> C[Calcul Δt\nnanos → secondes]
    C --> D[entity.update Δt\nphysique 3D CPU]
    D --> E[glClear]
    E --> F[NebulaField.draw\nFBO cache + blit]
    F --> G[Starfield.draw\npoints + étiquettes + HUD]
    G --> H[swapBuffers]
    H --> I([Frame affichée])
```

---

## Configuration et internationalisation

Au démarrage, `Main` charge deux ressources depuis le classpath :

| Ressource | Rôle |
|-----------|------|
| `/config.properties` | Dimensions de la fenêtre, code de langue |
| `i18n/messages_*.properties` | Titre localisé de la fenêtre |

Les codes de langue supportés sont **EN**, **FR**, **DE**, **ES**, **IT**.
La locale est construite via `Locale.of(langCode.toLowerCase())` et passée à
`ResourceBundle.getBundle("i18n.messages", locale)`.

---

> Chapitres suivants :
> - [02 — Pattern Entity / Behavior](02-entity-behavior.md)
> - [03 — ParticleSystem](03-particle-system.md)
> - [07 — Boucle de jeu](07-game-loop.md)
> - [12 — Pipeline OpenGL](12-opengl-pipeline.md)
