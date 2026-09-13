# Lab snapshot — 2026-09-13T19:01:01Z

Généré par `lab/snapshot.sh` (docker inspect, docker images, docker exec). Lecture seule.

## Notes

- KICKS : installation dans `Guide_installation_KICKS_1.5.0_TK5_OculiX.pdf`, conservation dans `KICKS.md`.
- Snapshot KICKS : `oculix-lab/target-mainframe-kicks:kicks-installed-2026-08-27`. Le compose est encore sur `build:`.

## Images

| Image | ID | Taille | Créée |
|---|---|---|---|
| oculix-lab/target-mainframe-kicks:kicks-installed-2026-08-27 | 24a8bd1b6b2a | 1.58GB | 2026-09-13 20:36:15 +0200 CEST |
| oculix-mainframe-lab-target-mainframe-kicks:latest | acc2e65e77d7 | 1.28GB | 2026-08-27 15:28:59 +0200 CEST |
| oculix-mainframe-lab-target-mainframe:latest | 3de860ad53b7 | 1.27GB | 2026-04-07 23:04:30 +0200 CEST |
| oculix-mainframe-lab-oculix-runner:latest | 23fa163a716e | 1.7GB | 2026-04-07 15:45:12 +0200 CEST |

## Conteneurs

### target-mainframe

- image : `oculix-mainframe-lab-target-mainframe`
- créé : 2026-04-07T21:09:52.439568981Z
- état : running (exit 0)
- restart : no · mémoire : 1073741824 · nano-cpus : 1000000000
- ports : 3270/tcp→3270 5900/tcp→5900 6080/tcp→6080 
- entrypoint : `[/entrypoint.sh]`
- réseaux : oculix-mainframe-lab_lab-net 
- montages : 0
- env :
  - `DEBIAN_FRONTEND=noninteractive`
  - `DISPLAY=:0`
  - `LANG=C.UTF-8`


### target-mainframe-kicks

- image : `oculix-mainframe-lab-target-mainframe-kicks`
- créé : 2026-08-27T14:01:28.192259105Z
- état : exited (exit 137)
- restart : unless-stopped · mémoire : 1073741824 · nano-cpus : 1000000000
- ports : 3270/tcp→3271 5900/tcp→5901 6080/tcp→6081 8038/tcp→8039 
- entrypoint : `[/entrypoint.sh]`
- réseaux : oculix-mainframe-lab_lab-net 
- montages : 0
- env :
  - `DEBIAN_FRONTEND=noninteractive`
  - `DISPLAY=:0`
  - `LANG=C.UTF-8`


### oculix-runner

- image : `oculix-mainframe-lab-oculix-runner`
- créé : 2026-04-07T20:55:11.977933528Z
- état : running (exit 0)
- restart : unless-stopped · mémoire : 2147483648 · nano-cpus : 2000000000
- ports : 8765/tcp→8765 
- entrypoint : `[/entrypoint.sh]`
- réseaux : oculix-mainframe-lab_lab-net 
- montages : 0
- env :
  - `TARGET_VNC_HOST=target-mainframe`
  - `TARGET_VNC_PORT=5900`
  - `RUNNER_WS_PORT=8765`
  - `JAVA_HOME=/opt/java/openjdk`
  - `LANG=C.UTF-8`
  - `LANGUAGE=en_US:en`
  - `LC_ALL=en_US.UTF-8`
  - `JAVA_VERSION=jdk-17.0.18+8`
  - `DEBIAN_FRONTEND=noninteractive`
  - `OCULIX_HOME=/opt/oculix`
  - `PYTHONUNBUFFERED=1`


## Réseau

- `oculix-mainframe-lab_lab-net` · driver bridge · 172.18.0.0/16

## Dans le runner

```
openjdk version "17.0.18" 2026-01-20

95bd353ddd92af9779845c83c36646bc5e5ca5e767196818615444c02d49533d  /opt/oculix/oculix-4.0.0-release.jar
f4b0b50c8e413094e78cd1d8fed02ae65f62f8c53ed00da0562fdedf4acff729  /opt/oculix/oculix-before-migration.jar
e8051cc0f8b6301ca2a31eccfa06d659b789f40100f679004b616337a792d2d8  /opt/oculix/oculix-runner-service.jar
a71de8a6c38f70813e63a621d58876d7d9ceb637f4983e48550600f59f13b71e  /opt/oculix/oculix.jar

libxi6:amd64 2:1.8-1build1
libxtst6:amd64 2:1.2.3-1build4
xauth 1:1.1-1build2
xvfb 2:21.1.4-2ubuntu1.7~22.04.16
```
