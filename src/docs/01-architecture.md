# Chapitre 1 — Architecture générale

## Vue d'ensemble

**demo001** est une application de bureau Java/Swing qui anime un champ d'étoiles 3D en vol
continu. Elle est structurée autour d'un moteur de rendu minimal, sans dépendance externe,
compilée avec Java 26 et packagée via un script `build.sh` maison.

L'architecture repose sur trois couches :

1. **Infrastructure applicative** (`Main`) — chargement de la configuration, localisation,
   gestion de la fenêtre Swing et cadençage de la boucle de jeu.
2. **Modèle de scène** (`Entity`, `Behavior`) — graphe d'objets génériques avec composition
   de comportements.
3. **Comportements métier** (`ParticleSystem`, `StarfieldBehavior`) — simulation physique
   et rendu du champ d'étoiles.

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
        -createAndShowWindow()
        -startGameLoop(GamePanel)
        -loadConfig() Properties
        -loadTitle(Locale) String
    }

    class GamePanel {
        -List~Entity~ entities
        +paintComponent(Graphics)
    }

    class Entity {
        +double x, y
        +double width, height
        +double dx, dy
        -List~Behavior~ behaviors
        +addBehavior(Behavior)
        +update(double dt)
        +draw(Graphics2D)
    }

    class Behavior {
        <<interface>>
        +update(Entity, double dt)
        +draw(Entity, Graphics2D)
    }

    class ParticleSystem {
        +ParticleSystem(int width, int height, InputState input, long seed)
    }

    class CameraState {
        -double velYaw, velPitch, velRoll
        +double cosYaw, sinYaw, cosPitch, sinPitch, cosRoll, sinRoll
        +update(double dt)
    }

    class StarfieldBehavior {
        -double[] sx, sy, sz
        -double[] travelSpeed
        -Color[] starColor
        -float[] brightness, baseSize
        -String[] starName
        -long seed
        -long spawnCounter
        +update(Entity, double dt)
        +draw(Entity, Graphics2D)
        -initStar(int i, boolean scatter)
        -subSeed(long seed, long n)$ long
    }

    class MagellanicCloudsBehavior {
        -double[] vx, vy, vz
        -float[] size, alpha
        -BufferedImage[] sprites
        +update(Entity, double dt)
        +draw(Entity, Graphics2D)
    }

    class StarNameGenerator {
        +generate(Random rng)$ String
    }

    Main --> GamePanel : crée
    Main "1" --> "*" Entity : possède
    GamePanel --> "*" Entity : dessine
    Entity "1" --> "*" Behavior : délègue
    ParticleSystem --|> Entity : étend
    StarfieldBehavior ..|> Behavior : implémente
    MagellanicCloudsBehavior ..|> Behavior : implémente
    ParticleSystem --> CameraState : intègre 1×/frame
    ParticleSystem --> StarfieldBehavior : instancie
    ParticleSystem --> MagellanicCloudsBehavior : instancie (arrière-plan)
    StarfieldBehavior --> CameraState : lit cos/sin
    MagellanicCloudsBehavior --> CameraState : lit cos/sin
    StarfieldBehavior --> StarNameGenerator : nomme les étoiles
```

---

## Cycle de vie de l'application

```plantuml
@startuml
title Cycle de vie — demo001

participant "JVM / main thread" as JVM
participant "Main" as M
participant "Swing EDT" as EDT
participant "javax.swing.Timer" as T
participant "Entity / Behavior" as E

JVM -> M : main(args)
activate M
M -> M : new Main()\nloadConfig()\nloadTitle(locale)
M -> M : run(args)\ninitEntities()
M -> EDT : SwingUtilities.invokeLater()
deactivate M

activate EDT
EDT -> EDT : createAndShowWindow()\nnew JFrame() / new GamePanel()
EDT -> T : new Timer(16 ms, listener)
EDT -> T : start()
deactivate EDT

loop toutes les 16 ms
    T -> M : ActionListener.actionPerformed()
    activate M
    M -> E : entity.update(dt)
    M -> EDT : panel.repaint()
    deactivate M
    activate EDT
    EDT -> E : entity.draw(g2)
    deactivate EDT
end

EDT -> JVM : EXIT_ON_CLOSE → System.exit
@enduml
```

---

## Flux de données par frame

```mermaid
flowchart LR
    A([Timer tick\n≈16 ms]) --> B[Calcul Δt\nnanos → secondes]
    B --> C[entity.update Δt]
    C --> D[StarfieldBehavior\n.update — physique 3D]
    D --> E[panel.repaint]
    E --> F[paintComponent\nGraphics2D]
    F --> G[entity.draw g2]
    G --> H[StarfieldBehavior\n.draw — rendu 2D]
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
> - [07 — Boucle de jeu et Swing](07-game-loop.md)
