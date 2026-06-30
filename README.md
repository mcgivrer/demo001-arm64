# demo001 — Starfield 3D

Une animation de vol à travers un champ d'étoiles en 3D, développée en Java/Swing.

![Java 26](https://img.shields.io/badge/Java-26-blue) ![License MIT](https://img.shields.io/badge/license-MIT-green)

## Aperçu

La caméra dérive aléatoirement (lacet, tangage, roulis) à travers 500 étoiles réparties en 3D.
Les étoiles sont colorées selon la classification spectrale de Harvard (M rouge → O bleu géant)
avec des probabilités réalistes, une parallaxe vraie et une loi en inverse du carré pour la luminosité.

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

## Structure

```
src/main/java/
├── Main.java               # Point d'entrée, boucle de jeu, fenêtre Swing
├── Entity.java             # Objet de scène (position, vitesse, behaviors)
├── Behavior.java           # Interface update/draw
├── ParticleSystem.java     # Entity spécialisée pour les particules
└── StarfieldBehavior.java  # Rendu et physique du champ d'étoiles

src/main/resources/
├── config.properties       # Taille fenêtre et langue
└── i18n/messages*.properties
```

## License

Ce projet est distribué sous licence [MIT](LICENSE).
