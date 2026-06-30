# demo001 — Starfield 3D

Une animation de vol à travers un champ d'étoiles en 3D, développée en Java/Swing.

![Java 26](https://img.shields.io/badge/Java-26-blue) ![License MIT](https://img.shields.io/badge/license-MIT-green)

## Aperçu

La caméra survole 500 étoiles réparties en 3D — pilotable au clavier et à la souris, ou en dérive brownienne autonome (lacet, tangage, roulis).
Les étoiles sont colorées selon la classification spectrale de Harvard (M rouge → O bleu géant)
avec des probabilités réalistes, une parallaxe vraie et une loi en inverse du carré pour la luminosité.
Un HUD affiche en temps réel la puissance des moteurs (jauge) et la vitesse de croisière (en parsecs/seconde, fictive).

![Rendu avec HUD de propulsion](src/docs/illustrations/app-render-hud.svg)

## Prérequis

- [sdkman](https://sdkman.io/) — gestion du JDK
- Java 26 (Zulu) — installé automatiquement via `.sdkmanrc`

```bash
sdk env install   # installe Java 26 si absent
sdk env           # active la version du projet
```

## Build & lancement

```bash
./build.sh          # compile les sources → target/classes/
./build.sh jar      # compile + package → target/demo001.jar
./build.sh run      # lance l'application
./build.sh all      # compile puis lance
./build.sh clean    # supprime target/
```

## Configuration

`src/main/resources/config.properties` :

| Propriété              | Défaut | Description                        |
|------------------------|--------|------------------------------------|
| `app.window.width`     | 800    | Largeur de la fenêtre (px)         |
| `app.window.height`    | 600    | Hauteur de la fenêtre (px)         |
| `app.language.default` | EN     | Langue du titre (EN/FR/DE/ES/IT)   |

## Contrôles

| Touche / Action              | Effet                          |
|------------------------------|--------------------------------|
| ← → / A D                   | Yaw — virer gauche / droite    |
| ↑ ↓ / W S                   | Pitch — monter / descendre     |
| Q / E                        | Roll — rotation de l'horizon   |
| SPACE                        | Frein — stoppe la rotation     |
| Clic gauche + glisser        | Joystick analogique yaw + pitch |
| CTRL                         | Augmente la puissance moteur (thrust) |
| SHIFT                        | Diminue la puissance moteur (thrust)  |
| ESCAPE                       | Quitter (boîte de confirmation Oui/Non) |
| H                             | Afficher / masquer la grille d'aide des contrôles |

En l'absence d'entrée, la caméra reprend sa dérive brownienne autonome.

La puissance moteur pilote la vitesse d'avancement du champ d'étoiles et s'affiche
dans le HUD en bas à gauche : une jauge verticale (cyan → jaune → rouge selon le
régime) et une vitesse en parsecs/seconde, qui évolue proportionnellement à la
puissance moteur.

Un panneau d'aide semi-transparent, affiché par défaut en bas à droite, rappelle
l'ensemble de ces contrôles ; la touche **H** bascule son affichage.

## Structure

```
src/main/java/
├── Main.java               # Point d'entrée, boucle de jeu, fenêtre Swing
├── Entity.java             # Objet de scène (position, vitesse, behaviors)
├── Behavior.java           # Interface update/draw
├── InputState.java         # État partagé clavier/souris (yaw/pitch/roll, thrust, ...)
├── ParticleSystem.java     # Entity spécialisée pour les particules
└── StarfieldBehavior.java  # Rendu et physique du champ d'étoiles, throttle, HUD

src/main/resources/
├── config.properties       # Taille fenêtre et langue
└── i18n/messages*.properties
```

## License

Ce projet est distribué sous licence [MIT](LICENSE).
