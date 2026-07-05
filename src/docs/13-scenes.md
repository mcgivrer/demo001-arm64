# Chapitre 13 — Scenes et transitions

## Objectif

Le moteur introduit une abstraction `Scene` pour séparer les écrans applicatifs
(titre, menu, simulation, carte, etc.) sans surcharger `Main`. Une scène est
responsable de son cycle de vie (`init`, `resize`, `update`, `draw`), de ses
entités internes et de ses demandes de navigation.

Dans l'état actuel :

- `TitleScene` affiche l'écran titre et propose de démarrer le voyage
- `TravelScene` encapsule l'ancienne simulation (via `ParticleSystem`)
- les transitions sont modélisées (`SceneTransition`, `SceneTransitionEffect`) et
  déjà transportées jusqu'au gestionnaire, avec exécution immédiate pour l'instant

![Flux des scenes](illustrations/scene-flow.svg)

---

## Contrat Scene

```java
public interface Scene {
    default void init(RenderContext ctx) {}
    default void resize(int width, int height) {}
    void update(double dt);
    void draw(RenderContext ctx);
    default SceneTransition pollTransition() { return null; }
    default void dispose() {}
}
```

`Main` agit comme un mini scene-manager : il appelle le cycle de vie de la
scène active, lit sa transition éventuelle, puis exécute `switchScene(...)`.

```mermaid
flowchart LR
    A[GLFW pollEvents] --> B[Main update dt]
    B --> C[activeScene.update dt]
    C --> D{pollTransition}
    D -- null --> E[activeScene.draw]
    D -- request --> F[switchScene target]
    F --> G[newScene.init + resize]
    G --> E
    E --> H[swapBuffers]
```

---

## Types de scenes implémentées

### TitleScene

- compose l'écran avec des entités UI : `TextObject` (titre/sous-titre)
  et des contrôles héritant de `ControlUI` (dont `ButtonObject`)
- navigation clavier entre boutons via `TAB` / `SHIFT+TAB`
- navigation clavier également via les flèches (`↑ ↓ ← →`)
- activation d'un bouton par `ENTREE` (focus courant)
- activation souris par clic dans la hitbox du bouton
- bouton `Demarrer` -> `SceneTransition.fadeTo("travel", 0.45)`
- bouton `Quitter` -> `SceneTransition.cutTo("quit")`

### TravelScene

- contient sa liste d'entités de simulation
- instancie `ParticleSystem` (code existant)
- délègue `init/resize/update/draw` à ses entités
- consomme `ESCAPE` et émet une transition de retour vers `TitleScene`

```plantuml
@startuml
title Architecture Scene

interface Scene {
  +init(RenderContext)
  +resize(int, int)
  +update(double)
  +draw(RenderContext)
  +pollTransition() SceneTransition
  +dispose()
}

class Main {
  -Scene activeScene
  -switchScene(sceneId, ctx)
  -applyTransition(SceneTransition, ctx)
}

class TitleScene {
  -InputState input
  -List~Entity~ entities
  -List~ButtonObject~ buttons
  -SceneTransition pendingTransition
  +update(dt)
  +draw(ctx)
}

class TextObject {
  -String text
  +draw(RenderContext)
}

class ButtonObject {
  -String label
  -boolean focused
  -boolean hovered
  +contains(x, y) boolean
  +activate()
  +draw(RenderContext)
}

abstract class ControlUI {
  -boolean focused
  -boolean hovered
  +setFocused(boolean)
  +setHovered(boolean)
  +contains(x, y) boolean
  +activate()
}

class TravelScene {
  -List~Entity~ entities
  +update(dt)
  +draw(ctx)
}

class SceneTransition {
  -String targetSceneId
  -SceneTransitionEffect effect
  -double durationSeconds
}

enum SceneTransitionEffect {
  CUT
  FADE
}

Main --> Scene : pilote
Scene <|.. TitleScene
Scene <|.. TravelScene
TitleScene --> SceneTransition : emet
TitleScene --> TextObject : compose
TitleScene --> ControlUI : compose
ControlUI <|-- ButtonObject
TravelScene --> Entity : contient
SceneTransition --> SceneTransitionEffect
@enduml
```

---

## Modèle de transition

Une transition transporte déjà les paramètres nécessaires aux effets futurs :

- scène cible
- type (`CUT`, `FADE`)
- durée

Mathématiquement, un fondu futur peut utiliser :

<math xmlns="http://www.w3.org/1998/Math/MathML" display="block">
  <mrow>
    <mi>α</mi>
    <mo>(</mo><mi>t</mi><mo>)</mo>
    <mo>=</mo>
    <mfrac>
      <mi>t</mi>
      <mi>T</mi>
    </mfrac>
    <mtext>, avec </mtext>
    <mn>0</mn><mo>≤</mo><mi>t</mi><mo>≤</mo><mi>T</mi>
  </mrow>
</math>

et un mix de scènes (cross-fade) :

<math xmlns="http://www.w3.org/1998/Math/MathML" display="block">
  <mrow>
    <mtext>Color</mtext>
    <mo>=</mo>
    <mrow>
      <mo>(</mo><mn>1</mn><mo>-</mo><mi>α</mi><mo>)</mo>
      <mo>·</mo><msub><mi>C</mi><mtext>from</mtext></msub>
      <mo>+</mo>
      <mi>α</mi><mo>·</mo><msub><mi>C</mi><mtext>to</mtext></msub>
    </mrow>
  </mrow>
</math>

Actuellement, `Main.applyTransition(...)` effectue un switch immédiat ; le
point d'extension est en place pour ajouter un vrai rendu temporel.

---

## Points d'extension

- ajouter `MenuScene`, `MapScene`, `SettingsScene` sans toucher aux behaviors
- implémenter un `TransitionController` (fade, wipe, zoom)
- sérialiser un contexte partagé inter-scenes (profil joueur, progression)
- ajouter un routage par identifiants de scène dans la configuration
