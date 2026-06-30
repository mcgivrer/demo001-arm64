# Chapitre 8 — Contrôle interactif : clavier et souris

## Motivation

Par défaut, la caméra dérive librement selon un processus brownien (voir
[chapitre 5](05-rotations-3d.md)). Le chapitre 8 décrit comment l'utilisateur peut
reprendre la main et piloter la caméra comme un vaisseau spatial, en utilisant le
clavier et/ou la souris, sans rompre l'animation ambiante lorsqu'aucune entrée n'est
active.

---

## Vue d'ensemble des composants

```mermaid
classDiagram
    class InputState {
        +boolean yawLeft
        +boolean yawRight
        +boolean pitchUp
        +boolean pitchDown
        +boolean rollLeft
        +boolean rollRight
        +boolean brake
        +boolean thrustUp
        +boolean thrustDown
        +boolean showHelp
        +boolean mouseDragging
        +double mouseNormX
        +double mouseNormY
    }

    class GamePanel {
        -InputState input
        -String exitTitle
        -String exitMessage
        +keyPressed(KeyEvent)
        +keyReleased(KeyEvent)
        +mousePressed(MouseEvent)
        +mouseReleased(MouseEvent)
        +mouseDragged(MouseEvent)
        -updateMouseNorm(MouseEvent)
        -confirmExit()
    }

    class StarfieldBehavior {
        -InputState input
        +update(Entity, double dt)
        +draw(Entity, Graphics2D)
        -drawControlsHelp(Graphics2D)
    }

    GamePanel --> InputState : écrit
    StarfieldBehavior --> InputState : lit
```

`InputState` est un objet de valeur mutable partagé entre `GamePanel` (producteur)
et `StarfieldBehavior` (consommateur). Comme `javax.swing.Timer` et les événements
Swing s'exécutent tous sur l'EDT, il n'y a pas de condition de course — aucun `volatile`
ni verrou n'est nécessaire.

---

## Mapping des contrôles

| Action          | Clavier              | Souris                          |
|-----------------|---------------------|---------------------------------|
| Yaw gauche      | ← Arrow, A           | —                               |
| Yaw droite      | → Arrow, D           | —                               |
| Pitch haut      | ↑ Arrow, W           | —                               |
| Pitch bas       | ↓ Arrow, S           | —                               |
| Roll gauche     | Q                    | —                               |
| Roll droite     | E                    | —                               |
| Frein           | SPACE                | —                               |
| Joystick yaw+pitch | —               | Clic gauche maintenu + glisser  |
| Puissance moteur +| CTRL                 | —                               |
| Puissance moteur -| SHIFT                | —                               |
| Quitter (confirmation) | ESCAPE          | —                               |
| Afficher/masquer l'aide | H              | —                               |

La puissance moteur (`thrustUp`/`thrustDown`) pilote la vitesse d'avancement du
vaisseau et alimente le HUD — voir [chapitre 9](09-thrust-engine.md).

---

## Grille d'aide des contrôles — H

`InputState.showHelp` est un booléen affiché à `true` par défaut : la grille d'aide
est donc **visible au démarrage**, sans action de l'utilisateur. Comme ESCAPE, H ne
pilote pas un état moteur continu mais une **bascule** (toggle) — appuyer une fois
inverse `showHelp`, peu importe la durée de l'appui.

La répétition de touche du système d'exploitation (key-repeat) pose un problème
classique pour un toggle : tant que H reste enfoncée, l'OS envoie plusieurs
`KEY_PRESSED` consécutifs, ce qui ferait clignoter l'affichage si chaque événement
inversait `showHelp`. `GamePanel` filtre ce phénomène avec un drapeau local
`hKeyDown`, remis à `false` uniquement sur `keyReleased` :

```java
private void toggleHelp() {
    if (!hKeyDown) {
        input.showHelp = !input.showHelp;
        hKeyDown = true;
    }
}
```

Le rendu lui-même est délégué à `StarfieldBehavior.drawControlsHelp()`, appelé en
toute fin de `draw()` (après le HUD de propulsion) si `input.showHelp` est vrai : un
panneau semi-transparent arrondi, ancré en bas à droite du panel, énumère
l'intégralité du mapping clavier/souris ci-dessus.

---

## Quitter l'application — ESCAPE

Contrairement aux autres touches, ESCAPE ne modifie pas `InputState` : `keyPressed`
appelle directement `GamePanel.confirmExit()`, qui ouvre une boîte de dialogue modale
(`JOptionPane.showConfirmDialog`, `YES_NO_OPTION`) avec un titre et un message
localisés (clés i18n `app.exit.confirm.title` / `app.exit.confirm.message`, résolues
par `Main.getMessage(key, fallback)` puis injectées dans `GamePanel` au moment de sa
construction). Si l'utilisateur confirme, `System.exit(0)` est appelé ; sinon
l'animation reprend normalement — le `javax.swing.Timer` continue de tourner pendant
que la boîte de dialogue est affichée, simplement gelé le temps que l'EDT soit occupé
par la modale.

---

## Modèle de vitesse angulaire hybride

Trois modes s'appliquent dans `StarfieldBehavior.update()`, par ordre de priorité :

### 1. Frein (SPACE)

Les vitesses angulaires décroissent exponentiellement vers zéro :

$$\omega_{n+1} = \omega_n \cdot \max\!\left(0,\; 1 - k_{\text{brake}} \cdot \Delta t\right)$$

avec $k_{\text{brake}} = 8\ \text{s}^{-1}$.

```xml
<math xmlns="http://www.w3.org/1998/Math/MathML">
  <msub><mi>ω</mi><mrow><mi>n</mi><mo>+</mo><mn>1</mn></mrow></msub>
  <mo>=</mo>
  <msub><mi>ω</mi><mi>n</mi></msub>
  <mo>·</mo>
  <mo>max</mo><mo>(</mo><mn>0</mn><mo>,</mo>
  <mn>1</mn><mo>-</mo><msub><mi>k</mi><mi>brake</mi></msub><mo>·</mo><mi>Δt</mi>
  <mo>)</mo>
</math>
```

### 2. Contrôle actif (touche ou souris)

Une vitesse cible $\omega^*$ est calculée depuis les entrées actives. La vitesse réelle
converge vers cette cible par interpolation linéaire (lerp) :

$$\omega_{n+1} = \omega_n + \left(\omega^* - \omega_n\right) \cdot k_{\text{lerp}} \cdot \Delta t$$

avec $k_{\text{lerp}} = 4\ \text{s}^{-1}$ (temps de réponse ~0,25 s).

La cible clavier est binaire : $\omega^* \in \{-\omega_{\max}, 0, +\omega_{\max}\}$.

La cible souris est analogique :

$$\omega^*_{\text{yaw}} = \hat{x}_{\text{mouse}} \cdot \omega_{\max}, \quad
  \omega^*_{\text{pitch}} = \hat{y}_{\text{mouse}} \cdot \omega_{\max}$$

où $\hat{x}, \hat{y} \in [-1, 1]$ sont les coordonnées normalisées relatives au centre
du panel, avec une zone morte de 0,08.

```xml
<math xmlns="http://www.w3.org/1998/Math/MathML">
  <msub><mi>ω</mi><mrow><mi>n</mi><mo>+</mo><mn>1</mn></mrow></msub>
  <mo>=</mo>
  <msub><mi>ω</mi><mi>n</mi></msub>
  <mo>+</mo>
  <mo>(</mo>
  <msup><mi>ω</mi><mo>*</mo></msup>
  <mo>-</mo>
  <msub><mi>ω</mi><mi>n</mi></msub>
  <mo>)</mo>
  <mo>·</mo>
  <msub><mi>k</mi><mi>lerp</mi></msub>
  <mo>·</mo>
  <mi>Δt</mi>
</math>
```

### 3. Dérive brownienne (aucune entrée)

Comportement autonome original — voir [chapitre 5](05-rotations-3d.md).

---

## Flowchart — logique de contrôle dans update()

```mermaid
flowchart TD
    A([update dt]) --> B{SPACE pressé ?}
    B -- oui --> C[Décroissance rapide\nω *= max(0, 1 − 8·dt)]
    B -- non --> D{Touche ou\nsouris active ?}
    D -- oui --> E[Calcul cible ω*\nclavier OU souris joystick]
    E --> F[Lerp : ω += (ω*-ω)·4·dt]
    D -- non --> G[Dérive brownienne\nω += N(0,σ²)·dt]
    G --> H[Clamp ±MAX_VEL]
    C --> I[Calcul angles frame\nα = ω × dt]
    F --> I
    H --> I
    I --> J[Rotations + forward travel\npour chaque étoile]
```

---

## Coordonnées normalisées de la souris

Le glisser de la souris est converti en coordonnées de joystick :

$$\hat{x} = \text{clamp}\!\left(\frac{x_{\text{mouse}} - c_x}{c_x},\; -1,\; 1\right)$$

avec $c_x = \text{width}/2$ (centre du panel). Idem pour $\hat{y}$.

Un seuil de zone morte $d = 0.08$ filtre les micro-mouvements involontaires :
si $|\hat{x}| < d$, la composante yaw est ignorée.

---

## Extrait de code — StarfieldBehavior.update()

```java
boolean anyKey = input.yawLeft || input.yawRight || input.pitchUp
               || input.pitchDown || input.rollLeft || input.rollRight;
boolean mouseActive = input.mouseDragging
                    && (Math.abs(input.mouseNormX) > MOUSE_DEAD
                        || Math.abs(input.mouseNormY) > MOUSE_DEAD);

if (input.brake) {
    double decay = Math.max(0.0, 1.0 - BRAKE_DECAY * dt);
    velYaw *= decay; velPitch *= decay; velRoll *= decay;
} else if (anyKey || mouseActive) {
    double tYaw = 0, tPitch = 0, tRoll = 0;
    if (input.yawLeft)   tYaw   = -MAX_VEL;
    if (input.yawRight)  tYaw   = +MAX_VEL;
    // ... (pitch, roll similaires)
    if (mouseActive) {
        tYaw   = input.mouseNormX * MAX_VEL;
        tPitch = input.mouseNormY * MAX_VEL;
    }
    velYaw   += (tYaw   - velYaw)   * LERP_RATE * dt;
    velPitch += (tPitch - velPitch) * LERP_RATE * dt;
    velRoll  += (tRoll  - velRoll)  * LERP_RATE * dt;
} else {
    // Brownian drift (original)
    velYaw   += rng.nextGaussian() * DRIFT_ACC * dt;
    // ...
}
```

---

> Voir aussi :
> - [05 — Rotations 3D](05-rotations-3d.md)
> - [07 — Boucle de jeu et intégration Swing](07-game-loop.md)
> - [09 — Propulsion : puissance moteur et HUD](09-thrust-engine.md)
> - [01 — Architecture générale](01-architecture.md)
