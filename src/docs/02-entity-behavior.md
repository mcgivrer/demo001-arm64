# Chapitre 2 — Pattern Entity / Behavior

## Motivation : composition plutôt qu'héritage

Un moteur de jeu naïf modélise ses objets par héritage :
`Etoile extends ObjetRendu extends Objet3D`. Cette hiérarchie rigide pose problème dès
qu'un objet doit cumuler plusieurs capacités indépendantes (rendu, physique, IA…).

Le pattern **Entity / Behavior** (ou *Component* dans la littérature Unity/ECS) inverse
la logique : une `Entity` est un **conteneur vide** auquel on attache dynamiquement des
`Behavior`. Chaque `Behavior` encapsule une responsabilité unique et ne connaît pas les
autres. L'entité délègue simplement `update` et `draw` à tous ses comportements dans
l'ordre d'insertion.

![Entity Behavior pattern](illustrations/entity-behavior-pattern.svg)

---

## Diagramme UML de classes

```plantuml
@startuml
title Entity / Behavior — Modèle de classes

interface Behavior {
    + update(entity : Entity, dt : double) : void
    + draw(entity : Entity, g : Graphics2D) : void
}

class Entity {
    + x  : double
    + y  : double
    + width  : double
    + height : double
    + dx : double
    + dy : double
    - behaviors : List<Behavior>
    --
    + Entity(x, y, width, height)
    + addBehavior(b : Behavior) : void
    + update(dt : double) : void
    + draw(g : Graphics2D) : void
}

class StarfieldBehavior {
    - sx[] sy[] sz[] : double
    - velYaw velPitch velRoll : double
    --
    + update(entity, dt)
    + draw(entity, g)
}

class ParticleSystem {
    + ParticleSystem(width, height)
}

Entity "1" o-- "*" Behavior : behaviors
StarfieldBehavior ..|> Behavior
ParticleSystem --|> Entity
ParticleSystem ..> StarfieldBehavior : <<crée et injecte>>
@enduml
```

---

## Protocole update / draw

Chaque frame, la boucle de jeu appelle successivement `update(dt)` puis `draw(g2)`
sur chaque `Entity`. L'entité propage ces appels à la liste de ses `Behavior` :

```mermaid
sequenceDiagram
    participant Timer
    participant Entity
    participant B1 as Behavior 1
    participant B2 as Behavior 2

    Timer->>Entity: update(dt)
    Entity->>B1: update(entity, dt)
    B1-->>Entity: (modifie état)
    Entity->>B2: update(entity, dt)
    B2-->>Entity: (modifie état)

    Timer->>Entity: draw(g2)
    Entity->>B1: draw(entity, g2)
    B1-->>Entity: (dessine)
    Entity->>B2: draw(entity, g2)
    B2-->>Entity: (dessine)
```

---

## Code source — Entity

```java
public class Entity {
    public double x, y, width, height, dx, dy;
    private final List<Behavior> behaviors = new ArrayList<>();

    public Entity(double x, double y, double width, double height) {
        this.x = x; this.y = y;
        this.width = width; this.height = height;
    }

    public void addBehavior(Behavior b) { behaviors.add(b); }

    public void update(double dt) {
        for (Behavior b : behaviors) b.update(this, dt);
    }

    public void draw(Graphics2D g) {
        for (Behavior b : behaviors) b.draw(this, g);
    }
}
```

---

## Code source — interface Behavior

```java
public interface Behavior {
    void update(Entity entity, double dt);
    void draw(Entity entity, Graphics2D g);
}
```

---

## Avantages du pattern dans ce projet

| Critère | Héritage seul | Entity + Behavior |
|---------|--------------|-------------------|
| Ajout d'un nouveau comportement | Sous-classe | `new MyBehavior()` + `addBehavior()` |
| Cumul de comportements | Héritage multiple interdit en Java | Liste illimitée |
| Testabilité | Dépendances couplées | Chaque `Behavior` est testable isolément |
| Réutilisation | Hiérarchie figée | Un `Behavior` partageable entre `Entity` différentes |

---

> Voir aussi :
> - [03 — ParticleSystem](03-particle-system.md) — utilisation concrète du pattern
> - [05 — Rotations 3D](05-rotations-3d.md) — logique interne de `StarfieldBehavior.update`
