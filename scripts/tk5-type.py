# -*- coding: utf-8 -*-
from sikuli import *

print "[runner] Connexion a target-mainframe:5900"

vnc = vncStart(
    "target-mainframe",
    port=5900,
    connectionTimeout=10,
    timeout=1000,
    password=None
)

try:
    if vnc is None or not vnc.isRunning():
        raise RuntimeError("Connexion VNC non etablie")

    print "[runner] Connexion etablie"
    wait(2)

    print "[runner] Saisie de LOGON HERC01"
    vnc.type("LOGON HERC01")
    vnc.type(Key.ENTER)
    wait(5)

    print "[runner] Saisie du mot de passe"
    vnc.type("CUL8TR")
    vnc.type(Key.ENTER)
    wait(3)

    print "[runner] Touches envoyees : verifie le resultat dans noVNC"
finally:
    if vnc is not None:
        vnc.stop()
