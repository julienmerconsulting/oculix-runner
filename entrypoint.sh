#!/bin/bash
# =============================================================================
#  Entrypoint oculix-runner
#  Sans argument : lance le service HTTP (JVM OculiX chaude) sur un Xvfb.
#  Avec une commande : l'execute telle quelle (docker exec / run interactif).
#
#  Pas de xvfb-run ici : il attend un SIGUSR1 de Xvfb pour lancer la commande,
#  et en PID 1 d'un conteneur ce signal ne lui parvient pas, il attend sans fin.
#  Xvfb est donc lance a la main, puis java prend le PID 1 (docker stop = SIGTERM
#  a la JVM, arret propre).
# =============================================================================
set -e

if [ "$#" -gt 0 ]; then
    exec "$@"
fi

DISPLAY_NUM="${RUNNER_DISPLAY_NUM:-99}"
export DISPLAY=":${DISPLAY_NUM}"

echo "[oculix-runner] jar OculiX  : ${OCULIX_JAR}"
echo "[oculix-runner] donnees     : ${RUNNER_DATA_DIR}"
echo "[oculix-runner] API HTTP    : http://0.0.0.0:${RUNNER_HTTP_PORT}"
echo "[oculix-runner] Xvfb        : ${DISPLAY}"

rm -f "/tmp/.X${DISPLAY_NUM}-lock"
Xvfb "${DISPLAY}" -screen 0 1280x1024x24 -nolisten tcp -noreset >/tmp/xvfb.log 2>&1 &

for i in $(seq 1 100); do
    [ -S "/tmp/.X11-unix/X${DISPLAY_NUM}" ] && break
    sleep 0.1
done
if [ ! -S "/tmp/.X11-unix/X${DISPLAY_NUM}" ]; then
    echo "[oculix-runner] Xvfb n'a pas demarre :" >&2
    cat /tmp/xvfb.log >&2
    exit 1
fi

exec java -Dfile.encoding=UTF-8 ${JAVA_OPTS:-} \
    -cp /opt/oculix/oculix-runner-service.jar:"${OCULIX_JAR}" \
    org.oculix.runner.Main
