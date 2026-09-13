# oculix-runner

Une JVM OculiX chaude derrière une API HTTP, avec une base SQLite qui trace tout : projets, cibles
VNC, scripts, suites, runs, lignes de log, steps, artefacts, clés, audit. Le jar OculiX n'est pas
modifié : le service le charge une fois et lui passe des scripts Jython. Après le premier run, un
script `print` tourne en 0,7 s au lieu de 15.

Le dépôt contient aussi le labo mainframe qui sert de cible : TK5 (MVS 3.8j sous Hercules) et
TK5 + KICKS, voir `lab/`.

## Démarrer

```
docker compose build oculix-runner
RUNNER_BOOTSTRAP_KEY=orx_ma_cle docker compose up -d oculix-runner
curl http://localhost:8765/health
```

Sans `RUNNER_BOOTSTRAP_KEY`, une clé admin est générée au premier démarrage et affichée une seule
fois dans `docker compose logs oculix-runner`. Seul son hash est en base.

Le moteur met 15 à 20 s à être prêt (`"engine":"ready"` dans `/health`). Les runs soumis avant
attendent en file.

## Premier run

```
K='X-Api-Key: orx_ma_cle'
B=http://localhost:8765

curl -s -X POST -H "$K" -H 'Content-Type: application/json' \
  -d '{"code":"lab","name":"Mainframe lab"}' $B/projects
curl -s -X POST -H "$K" -H 'Content-Type: application/json' \
  -d '{"name":"tk5","host":"target-mainframe","port":5900,"stage":"INT"}' $B/projects/1/targets
curl -s -X POST -H "$K" --data-binary @scripts/tk5-type.py \
  "$B/projects/1/scripts/raw?name=tk5-type"
curl -s -X POST -H "$K" -H 'Content-Type: application/json' \
  -d '{"project_code":"lab","script_id":1,"target_id":1}' $B/runs
curl -s -H "$K" $B/runs/1/log/stream
```

Le dernier appel affiche le log en direct et se termine par `--- run 1 passed (14371 ms)`.

## Écrire un script

Un script est du Jython OculiX ordinaire. Le service place devant lui un en-tête
(`service/src/main/resources/header.py`) qui fournit :

- `RUN` : id, nom, paramètres et cible du run, lus dans `run.json`.
- `PARAMS` : le dictionnaire passé dans `params` à la création du run.
- `TARGET` : `host`, `port`, `display`, `secret_ref` de la cible, et `password` résolu depuis la
  variable d'environnement nommée par `secret_ref`. `TARGET_VNC_HOST` et `TARGET_VNC_PORT` sont
  aussi posés dans `os.environ` pour les scripts existants.
- `step(label, status, detail)` : déclare une étape, statuts `START`, `PASS`, `FAIL`, `SKIP`,
  `INFO`. Un `START` suivi d'un `PASS` ou `FAIL` de même label ferme l'étape.

La connexion VNC reste dans le script : `VNCScreen.start(TARGET["host"], TARGET["port"], 10, 0)`.
Les numéros de ligne d'erreur renvoyés par l'API sont ceux du script, en-tête déduit.

## API

Tout demande `X-Api-Key` sauf `/health`. Portées : `read` (GET), `run` (scripts, suites, runs,
abort), `admin` (projets, cibles, clés, audit). `admin` inclut `run`, `run` inclut `read`.

| Méthode et route | Portée | Rôle |
|---|---|---|
| `GET /health` | aucune | état du moteur, run en cours, file |
| `GET /version` | read | versions du service et d'OculiX, hash du jar |
| `POST /projects`, `GET /projects`, `GET /projects/{id}` | admin / read | projets |
| `POST /projects/{id}/targets`, `GET /projects/{id}/targets`, `GET /targets/{id}`, `PUT /targets/{id}` | admin / read | cibles VNC |
| `GET /targets/{id}/check` | run | connexion TCP à la cible |
| `POST /projects/{id}/scripts` (JSON `name`, `content`) | run | créer un script |
| `POST /projects/{id}/scripts/raw?name=…&external_ref=…` (corps = le `.py`) | run | créer un script depuis un fichier |
| `GET /projects/{id}/scripts`, `GET /scripts/{id}`, `GET /scripts/{id}/content` | read | lire |
| `PUT /scripts/{id}` (JSON), `PUT /scripts/{id}/content` (corps = le `.py`) | run | nouvelle version |
| `POST /projects/{id}/suites` (`name`, `items`), `PUT /suites/{id}/items`, `GET /suites/{id}` | run / read | suites ordonnées |
| `POST /suites/{id}/run` | run | file tous les scripts de la suite |
| `GET /suite-runs/{id}`, `GET /projects/{id}/suite-runs`, `POST /suite-runs/{id}/abort` | read / run | exécutions de suite |
| `POST /runs` (`project_code` ou `project_id`, `script_id` ou `code`, `target_id`, `params`, `timeout_ms`) | run | un run |
| `GET /runs?project_id=&status=&limit=`, `GET /runs/{id}` | read | runs et leurs steps |
| `GET /runs/{id}/log?after=&wait=` | read | lignes de log, long-poll jusqu'à `wait` secondes |
| `GET /runs/{id}/log/stream` | read | log en texte, en continu jusqu'à la fin du run |
| `GET /runs/{id}/steps`, `GET /runs/{id}/artifacts`, `GET /artifacts/{id}` | read | steps et fichiers |
| `POST /runs/{id}/abort` | run | retire de la file ou interrompt le run en cours |
| `POST /keys` (`name`, `scopes`), `GET /keys`, `DELETE /keys/{id}` | admin | clés, la clé en clair n'est montrée qu'à la création |
| `GET /audit`, `GET /engine/events` | admin / read | journal des actions, événements du moteur |

Statuts d'un run : `queued`, `running`, `passed`, `failed`, `timeout`, `aborted`, `error`,
`skipped`. Un seul run tourne à la fois, les autres attendent en file dans l'ordre de création.
Une suite s'arrête au premier échec sauf `continue_on_failure` sur l'item.

## Données

`/workdir` (volume `runner-data`) : `runner.db`, `runs/` (dossiers `.sikuli` composés),
`artifacts/<projet>/<run>/` (le script exact de chaque run). Schéma dans
`service/src/main/resources/schema.sql`.

## Variables d'environnement

| Variable | Défaut | Rôle |
|---|---|---|
| `RUNNER_HTTP_PORT` | `8765` | port HTTP |
| `RUNNER_DATA_DIR` | `/workdir` | base, runs, artefacts |
| `OCULIX_JAR` | `/opt/oculix/oculix.jar` | le jar chargé |
| `RUNNER_DEBUG_LEVEL` | `3` | niveau de debug OculiX |
| `RUNNER_DEFAULT_TIMEOUT_MS` | `0` | timeout par défaut des runs, 0 = aucun |
| `RUNNER_BOOTSTRAP_KEY` | générée | clé admin du premier démarrage |
| `JAVA_OPTS` | vide | options JVM |

## Le jar OculiX

Par défaut, le build télécharge la release `4.0.0` de `oculix-org/Oculix` et vérifie son SHA-256.
Pour un autre jar (build local, release candidate), le copier dans `jars/oculix.jar` avant le
build : il prend le pas sur le téléchargement. Le dossier est ignoré par git.

À savoir avec la `4.0.0` : deux corrections faites depuis ne sont pas dedans, le bug ZRLE de
`tigervnc-java-oculix` 2.0.1 (#443) et `Key.ENTER` qui envoie un Linefeed sur x3270 au lieu
d'Entrée. Le logon TSO du labo a été validé avec un jar construit depuis `chore/global-bug-fixes`,
posé dans `jars/`. La release suivante d'OculiX les embarquera.

## Construire le service seul

```
cd service
mvn -Doculix.jar=/chemin/vers/oculixide-4.0.0-linux.jar package
```

Produit `target/oculix-runner-service.jar`. Lancement à la main :

```
xvfb-run -a -s "-screen 0 1280x1024x24" \
  java -cp target/oculix-runner-service.jar:/chemin/vers/oculix.jar org.oculix.runner.Main
```

## Labo mainframe

`lab/KICKS.md` : démarrer le TK5 avec KICKS depuis l'image publique, ou l'installer soi-même avec
le guide PDF. `lab/snapshot.sh` : photo à date des conteneurs. `scripts/` : les scripts du labo,
`tk5-type.py` (logon HERC01) et `oculix-check.py` (capture + OCR).
