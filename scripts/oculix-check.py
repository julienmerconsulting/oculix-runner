# -*- coding: utf-8 -*-
from sikuli import *
from org.sikuli.vnc import VNCScreen
from org.sikuli.support import Commons
from org.sikuli.script import OCR
from javax.imageio import ImageIO
from java.io import File
import os

host = os.environ.get("TARGET_VNC_HOST", "target-mainframe")
port = int(os.environ.get("TARGET_VNC_PORT", "5900"))

Commons.loadTesseract()
if not Commons.isTesseractLoaded():
    raise RuntimeError("Les natives Tesseract embarquees n'ont pas ete chargees")

print "[check] Tesseract embarque charge"
print "[check] Tessdata :", Commons.getTesseractDataPath()

vnc = VNCScreen.start(host, port, 10, 0)
try:
    if vnc is None or not vnc.isRunning():
        raise RuntimeError("Connexion VNC non etablie")

    wait(2)
    image = vnc.capture().getImage()
    if image.getWidth() <= 0 or image.getHeight() <= 0:
        raise RuntimeError("Capture VNC vide")

    File("/workdir").mkdirs()
    output = File("/workdir/oculix-check.png")
    if not ImageIO.write(image, "png", output):
        raise RuntimeError("Ecriture PNG impossible")

    print "[check] Capture : %s x %s" % (image.getWidth(), image.getHeight())
    text = OCR.readText(image)
    print "[check] OCR :"
    print text.encode("utf-8")
    if not text.strip():
        raise RuntimeError("OCR vide sur l'ecran courant")

    print "OCULIX_CHECK_OK"
finally:
    if vnc is not None:
        vnc.stop()
