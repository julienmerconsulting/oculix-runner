# =============================================================================
#  oculix-runner : execute des scripts Sikuli/Python contre une cible VNC
# -----------------------------------------------------------------------------
#  Image autonome qui contient :
#    - JRE 17 (pour OculiX/SikuliX)
#    - OculiX jar (telecharge depuis github.com/oculix-org/SikuliX1)
#    - Jython 2.7 (interpreteur Python embarque dans Sikuli)
#    - PaddleOCR (pour la reconnaissance de texte sur le mainframe)
#    - Python 3 + websockets (serveur de commandes/logs)
#  Pas besoin de display X local : OculiX se connecte au VNC du target par TCP.
# =============================================================================
FROM eclipse-temurin:17-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    OCULIX_HOME=/opt/oculix \
    PYTHONUNBUFFERED=1

# -----------------------------------------------------------------------------
# Dependances systeme : Python, libs OpenCV, outils reseau
# -----------------------------------------------------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
        wget \
        curl \
        ca-certificates \
        net-tools \
        libgl1 \
        libglib2.0-0 \
        libsm6 \
        libxext6 \
        libxrender1 \
        libfreetype6 \
        fontconfig \
    && rm -rf /var/lib/apt/lists/*

# -----------------------------------------------------------------------------
# Installation de OculiX
# A remplacer par l'URL officielle du release oculix-org/SikuliX1 quand publie.
# Pour le moment on prend SikuliX 2.0.5 comme placeholder.
# -----------------------------------------------------------------------------
WORKDIR /opt/oculix
RUN wget -q https://launchpad.net/sikuli/sikulix/2.0.5/+download/sikulixide-2.0.5.jar \
         -O oculix.jar
# Quand le release officiel est pret, remplacer par :
# RUN wget -q https://github.com/oculix-org/SikuliX1/releases/download/vX.Y.Z/oculix-X.Y.Z.jar -O oculix.jar

# -----------------------------------------------------------------------------
# Installation Python : websockets pour le serveur, paddleocr pour l'OCR
# (PaddleOCR est lourd ~300Mo de modeles, telecharges au premier run)
# -----------------------------------------------------------------------------
RUN python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
RUN pip install --no-cache-dir \
        websockets==12.0 \
        paddleocr==3.3.0 \
        paddlepaddle==2.6.2 \
        opencv-python-headless==4.10.0.84 \
        Pillow==10.2.0 \
        vncdotool==1.2.0

# -----------------------------------------------------------------------------
# Serveur de commandes : recoit des scripts via WebSocket, les execute,
# stream les logs en retour. Pour l'instant minimal : on l'enrichira plus tard.
# -----------------------------------------------------------------------------
COPY runner_server.py /opt/oculix/runner_server.py
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Repertoire de travail pour les scripts envoyes par l'utilisateur
RUN mkdir -p /tmp/scripts

# Variables d'environnement par defaut : cible VNC
ENV TARGET_VNC_HOST=target-mainframe \
    TARGET_VNC_PORT=5900 \
    RUNNER_WS_PORT=8765

EXPOSE 8765

ENTRYPOINT ["/entrypoint.sh"]
