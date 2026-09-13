# KICKS 1.5.0 sur TK5

Démarrer (image publique, KICKS déjà installé) :

```
docker pull ghcr.io/julienmerconsulting/target-mainframe-kicks:1.5.0-installed
docker compose up -d target-mainframe-kicks
```

Arrêter `target-mainframe` avant : 1 Go et 1 CPU chacun. Ports hôte : 3271 (TN3270), 5901 (VNC),
6081 (noVNC), 8039 (console Hercules). Compte TSO : HERC01 / CUL8TR.

KICKS démarre tout seul au logon de HERC01 (`HERC01.CMDPROC(MYLOGON)` lance la CLIST `KICKS`).
Ensuite : Ctrl+C pour CLEAR, `BTC0` pour le menu TAC, `KSSF` pour sortir vers ISPF, `LOGOFF` à
la fin. Pour retrouver le logon classique : supprimer le membre `MYLOGON`.

Installer soi-même sur un TK5 nu : `Guide_installation_KICKS_1.5.0_TK5_OculiX.pdf`, annexe A.
La seule édition à faire dans la distribution : `VOLUMES(PUB002)` → `VOLUMES(WORK01)` dans les
jobs `LOADMUR`, `LOADTAC`, `LOADSDB` (`KICKS.V1R5M0.INSTLIB`) et `LODINTRA`, `LODTEMP`
(`KICKSSYS.V1R5M0.INSTLIB`), parenthèse fermante comprise. Les objets KICKS ne sont pas dans ce
dépôt : sa licence n'autorise que la redistribution du paquet complet, ce que fait l'image.

Sauvegarder après une modification dans MVS (les DASD sont dans le conteneur, pas dans un volume) :

```
docker commit target-mainframe-kicks ghcr.io/julienmerconsulting/target-mainframe-kicks:1.5.0-installed
docker push ghcr.io/julienmerconsulting/target-mainframe-kicks:1.5.0-installed
```

Ne jamais faire `docker compose down`, `up --build` ni `--force-recreate` sur ce service sans avoir
poussé le commit avant.
