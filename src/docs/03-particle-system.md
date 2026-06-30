# Chapitre 3 — ParticleSystem

## Rôle

`ParticleSystem` est une **spécialisation d'`Entity`** dont le seul rôle est d'assembler
un système de particules prêt à l'emploi. Elle ne contient aucune logique de simulation
propre : tout le travail est délégué à un `Behavior` injecté dans son constructeur.

Ce design respecte le principe de **responsabilité unique** : `ParticleSystem` sait
*quels comportements* assembler ; `StarfieldBehavior` sait *comment* simuler et dessiner.

---

## Héritage et composition

```plantuml
@startuml
title ParticleSystem — héritage et composition

class Entity {
    + x, y, width, height : double
    + dx, dy : double
    - behaviors : List<Behavior>
    + addBehavior(Behavior)
    + update(dt)
    + draw(Graphics2D)
}

class ParticleSystem {
    + ParticleSystem(width : int, height : int)
}

interface Behavior {
    + update(Entity, double)
    + draw(Entity, Graphics2D)
}

class StarfieldBehavior {
    + StarfieldBehavior(width, height)
    + update(Entity, double)
    + draw(Entity, Graphics2D)
}

Entity <|-- ParticleSystem
Behavior <|.. StarfieldBehavior
ParticleSystem ..> StarfieldBehavior : <<new + addBehavior>>
@enduml
```

---

## Flux d'initialisation

```mermaid
flowchart TD
    A([Main.initEntities]) --> B[new ParticleSystem\nwidth, height]
    B --> C[super Entity\n0, 0, width, height]
    C --> D[new StarfieldBehavior\nwidth, height]
    D --> E[initStar × 500\nscatter = true]
    E --> F[addBehavior\nStarfieldBehavior]
    F --> G([Entité prête\ndans entities])
```

---

## Code source

```java
public class ParticleSystem extends Entity {
    public ParticleSystem(int width, int height) {
        super(0, 0, width, height);
        addBehavior(new StarfieldBehavior(width, height));
    }
}
```

La position `(0, 0)` et les dimensions `(width, height)` définissent le **domaine de
l'entité** — ici la totalité du panneau graphique. `StarfieldBehavior` lit ces valeurs
via `entity.width` / `entity.height` pour calculer les centres de projection `cx`, `cy`
et les facteurs d'échelle `projScaleX`, `projScaleY`.

---

## Extension possible

Pour ajouter un deuxième effet visuel (ex. météores, nébuleuse), il suffit d'un second
`addBehavior(...)` — sans modifier `Entity` ni `ParticleSystem` :

```java
public class ParticleSystem extends Entity {
    public ParticleSystem(int width, int height) {
        super(0, 0, width, height);
        addBehavior(new StarfieldBehavior(width, height));
        addBehavior(new MeteorBehavior(width, height)); // futur comportement
    }
}
```

Les deux `Behavior` seront appelés séquentiellement à chaque frame, dans l'ordre
d'insertion.

---

> Voir aussi :
> - [02 — Pattern Entity / Behavior](02-entity-behavior.md)
> - [04 — Classification spectrale](04-spectral-classification.md)
> - [05 — Rotations 3D](05-rotations-3d.md)
