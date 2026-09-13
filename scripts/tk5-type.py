# -*- coding: utf-8 -*-
from sikuli import *
from org.sikuli.vnc import VNCScreen
import os
import time

host = os.environ.get("TARGET_VNC_HOST", "target-mainframe")
port = int(os.environ.get("TARGET_VNC_PORT", "5900"))

t0 = time.time()
def mark(label):
    print "[runner] %-28s t+%6.2fs" % (label, time.time() - t0)

mark("Connexion a %s:%s" % (host, port))
vnc = VNCScreen.start(host, port, 10, 0)

try:
    if vnc is None or not vnc.isRunning():
        raise RuntimeError("Connexion VNC non etablie")

    mark("Connexion etablie")
    wait(2)

    mark("Saisie de HERC01")
    vnc.type("HERC01")
    vnc.type(Key.ENTER)
    mark("HERC01 + Enter envoyes")
    wait(5)

    mark("Saisie du mot de passe")
    vnc.type("CUL8TR")
    vnc.type(Key.ENTER)
    mark("Mot de passe + Enter envoyes")
    wait(3)

    mark("Touches envoyees : verifier le resultat dans noVNC")
finally:
    if vnc is not None:
        vnc.stop()
    mark("Fin du script")
