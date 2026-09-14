# KICKS 1.5.0 on TK5

Start (public image, KICKS already installed):

```
docker pull ghcr.io/julienmerconsulting/target-mainframe-kicks:1.5.0-installed
docker compose up -d target-mainframe-kicks
```

Stop `target-mainframe` first: 1 GB and 1 CPU each. Host ports: 3271 (TN3270), 5901 (VNC),
6081 (noVNC), 8039 (Hercules console). TSO account: HERC01 / CUL8TR.

KICKS starts by itself at HERC01's logon (`HERC01.CMDPROC(MYLOGON)` runs the `KICKS` CLIST).
Then: Ctrl+C for CLEAR, `BTC0` for the TAC menu, `KSSF` to leave for ISPF, `LOGOFF` when done.
To get the plain logon back: delete the `MYLOGON` member.

Install it yourself on a bare TK5: `Guide_installation_KICKS_1.5.0_TK5_OculiX.pdf`, appendix A.
The guide is in French; any AI translates it in a minute. The only edit to make in the
distribution: `VOLUMES(PUB002)` → `VOLUMES(WORK01)` in jobs `LOADMUR`, `LOADTAC`, `LOADSDB`
(`KICKS.V1R5M0.INSTLIB`) and `LODINTRA`, `LODTEMP` (`KICKSSYS.V1R5M0.INSTLIB`), closing
parenthesis included. KICKS objects are not in this repository: its license only allows
redistributing the complete package, which is what the image does.

Save after a change inside MVS (the DASD live in the container, not in a volume):

```
docker commit target-mainframe-kicks ghcr.io/julienmerconsulting/target-mainframe-kicks:1.5.0-installed
docker push ghcr.io/julienmerconsulting/target-mainframe-kicks:1.5.0-installed
```

Never run `docker compose down`, `up --build` or `--force-recreate` on this service without having
pushed the commit first.
