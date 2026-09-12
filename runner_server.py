#!/usr/bin/env python3
"""
=============================================================================
 runner_server.py
-----------------------------------------------------------------------------
 Serveur WebSocket minimal qui :
   1. Ecoute sur ws://0.0.0.0:8765
   2. Recoit un message JSON { "script": "...code python sikuli..." }
   3. Ecrit le script dans /tmp/scripts/run_<id>.sikuli/run_<id>.py
   4. Lance OculiX en mode runScript et stream stdout/stderr ligne par ligne
      au client via WebSocket
   5. Envoie un message final { "type": "done", "code": <exit_code> }

 Pour la phase de test manuel, tu peux aussi te connecter en bash dans le
 container et lancer OculiX a la main :
   docker exec -it oculix-runner bash
   java -jar /opt/oculix/oculix.jar -r /tmp/test.sikuli
=============================================================================
"""
import asyncio
import json
import os
import shutil
import subprocess
import uuid
from pathlib import Path

import websockets

OCULIX_JAR = os.environ.get("OCULIX_JAR", "/opt/oculix/oculix.jar")
WS_PORT = int(os.environ.get("RUNNER_WS_PORT", "8765"))
TARGET_HOST = os.environ.get("TARGET_VNC_HOST", "target-mainframe")
TARGET_PORT = os.environ.get("TARGET_VNC_PORT", "5900")
SCRIPTS_DIR = Path("/tmp/scripts")
SCRIPTS_DIR.mkdir(parents=True, exist_ok=True)


async def send(ws, payload: dict) -> None:
    """Envoi d'un message JSON au client, sans planter si la socket ferme."""
    try:
        await ws.send(json.dumps(payload))
    except websockets.ConnectionClosed:
        pass


async def stream_process(ws, proc: asyncio.subprocess.Process) -> int:
    """Lit stdout du process ligne par ligne et la pousse au client."""
    assert proc.stdout is not None
    while True:
        line = await proc.stdout.readline()
        if not line:
            break
        text = line.decode("utf-8", errors="replace").rstrip()
        await send(ws, {"type": "log", "line": text})
    return await proc.wait()


async def run_sikuli_script(ws, script_code: str) -> None:
    """
    Ecrit le script dans un dossier .sikuli temporaire, lance OculiX,
    stream les logs, nettoie a la fin.
    """
    run_id = uuid.uuid4().hex[:8]
    sikuli_dir = SCRIPTS_DIR / f"run_{run_id}.sikuli"
    sikuli_dir.mkdir(parents=True, exist_ok=True)
    script_path = sikuli_dir / f"run_{run_id}.py"

    # Header injecte automatiquement : connexion VNC vers la cible
    header = (
        "# Auto-injecte par oculix-runner\n"
        f"# Cible VNC : {TARGET_HOST}:{TARGET_PORT}\n"
        "# (l'API exacte de connexion VNC depend de la version OculiX,\n"
        "#  on l'ajustera apres premier test)\n"
        "from sikuli import *\n"
        "import time\n\n"
    )
    script_path.write_text(header + script_code, encoding="utf-8")

    await send(ws, {"type": "info", "msg": f"Script ecrit : {script_path}"})
    await send(ws, {"type": "info", "msg": "Lancement OculiX..."})

    # Lancement Java -jar oculix.jar -r <dossier.sikuli>
    cmd = [
        "java",
        "-jar",
        OCULIX_JAR,
        "-r",
        str(sikuli_dir),
        "--",
        f"--vnc-host={TARGET_HOST}",
        f"--vnc-port={TARGET_PORT}",
    ]

    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )

    exit_code = await stream_process(ws, proc)
    await send(ws, {"type": "done", "code": exit_code})

    # Nettoyage
    shutil.rmtree(sikuli_dir, ignore_errors=True)


async def handler(ws) -> None:
    """Une connexion = une session, peut enchainer plusieurs scripts."""
    await send(ws, {
        "type": "ready",
        "target": f"{TARGET_HOST}:{TARGET_PORT}",
        "oculix_jar": OCULIX_JAR,
    })

    async for raw in ws:
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            await send(ws, {"type": "error", "msg": "JSON invalide"})
            continue

        if msg.get("type") == "ping":
            await send(ws, {"type": "pong"})
            continue

        script = msg.get("script")
        if not script:
            await send(ws, {"type": "error", "msg": "champ 'script' manquant"})
            continue

        await run_sikuli_script(ws, script)


async def main() -> None:
    print(f"[runner] Ecoute sur ws://0.0.0.0:{WS_PORT}")
    print(f"[runner] Cible VNC configuree : {TARGET_HOST}:{TARGET_PORT}")
    print(f"[runner] OculiX jar : {OCULIX_JAR}")
    async with websockets.serve(handler, "0.0.0.0", WS_PORT):
        await asyncio.Future()  # run forever


if __name__ == "__main__":
    asyncio.run(main())
