<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHFKey

*Exécute des commandes quand le joueur appuie sur F, selon le monde, la région et la permission.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Exécute des commandes quand le joueur appuie sur F, selon le monde, la région et la permission.

Utilisé sur : **Opale**.

## Dépendances

- Optionnelles : `WorldGuard`, `PlaceholderAPI`, `SHNetwork` (mode réseau)

## Mode réseau

Avec **SHNetwork** installé (et `reseau.actif: true`, valeur par défaut), plusieurs serveurs de jeu partagent la liste des joueurs pour qui SHFKey est désactivé :

- la liste est dans MariaDB (table `shfkey_disabled`, une ligne par joueur désactivé) ; `/shfkey toggle` ajoute ou retire cette seule ligne, sur un fil à part ;
- `/shfkey toggle <joueur>` marche aussi pour un joueur connecté à un autre serveur : l'état est basculé en base, puis son serveur est prévenu par le bus (sujet `shfkey`) ; un joueur inconnu du réseau répond toujours « Joueur introuvable. » ;
- au départ d'un joueur, SHNetwork attend que ces écritures soient finies ;
- à son arrivée sur l'autre serveur, son état est relu en base une fois le départ confirmé ; d'ici là SHFKey est actif (valeur par défaut, jamais écrite en base) ;
- `data.yml` n'est plus lu ni écrit : `/shfkey import` le reprend une fois dans la base, sans écraser ce qui y est déjà ;
- les actions ne lancent que des commandes : `is go` est interceptée par le pont SuperiorSkyblock2 (SHSkyBridge), qui envoie le joueur sur le serveur de son île avec le point d'arrivée, et `spawn` ramène au serveur qui héberge le spawn. Chaque serveur garde la même liste d'actions, rien d'autre à régler.

Sans SHNetwork, ou sans les clés `reseau` dans la configuration, le plugin se comporte comme avant : liste locale dans `data.yml`. Si la base est injoignable, le joueur garde SHFKey actif. La table se crée toute seule au démarrage, ou à la main avec `sql/schema.sql`.

## Commandes

Sans argument, `/shfkey` affiche les sous-commandes que l'expéditeur a le droit d'utiliser. Elle marche depuis la console.

| Commande | Description | Permission |
|---|---|---|
| `/shfkey toggle <joueur>` | Active ou désactive SHFKey pour un joueur (même connecté à un autre serveur avec SHNetwork). | `shfkey.admin.toggle` |
| `/shfkey list` | Joueurs pour qui SHFKey est désactivé : la table partagée avec SHNetwork, sinon `data.yml` (au plus `list-max` lignes). | `shfkey.admin.list` |
| `/shfkey reload` | Relit `config.yml` (actions) et `messages.yml`. Si un fichier est invalide, l'ancienne configuration reste active ; `data.yml`, la base et la liste des joueurs désactivés ne sont pas touchés ; la section `reseau` n'est lue qu'au démarrage. | `shfkey.admin.reload` |
| `/shfkey import` | Avec SHNetwork : reprend `data.yml` dans la base partagée. | `shfkey.admin.import` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shfkey.admin` | Toutes les commandes d'administration. | `op` |
| `shfkey.admin.toggle` · `.list` · `.reload` · `.import` | Une permission par sous-commande. | `op` |

## Configuration

Fichiers par défaut, avec des valeurs d'exemple. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHFKey/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
