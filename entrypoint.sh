#!/bin/bash
# =============================================================================
#  Entrypoint oculix-runner
#  Mode par defaut : lance le serveur WebSocket qui ecoute les commandes.
#  Mode interactif (CMD bash) : tu rentres dedans avec docker exec et tu joues.
# =============================================================================
set -e

echo "[oculix-runner] Cible VNC : ${TARGET_VNC_HOST}:${TARGET_VNC_PORT}"
echo "[oculix-runner] Port WebSocket : ${RUNNER_WS_PORT}"

# Si une commande est passee au container, on l'execute (mode interactif)
if [ "$#" -gt 0 ]; then
    exec "$@"
fi

# Sinon on demarre le serveur WebSocket
echo "[oculix-runner] Demarrage du serveur de commandes..."
exec python3 /opt/oculix/runner_server.py
