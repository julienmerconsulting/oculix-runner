#!/bin/sh
# Photo à date du labo Docker (WSL) : conteneurs, images, réseau, contenu du runner.
# Ne lance rien, ne modifie rien. Usage :  sh lab/snapshot.sh > lab/SNAPSHOT.md
set -e
echo "# Lab snapshot — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo
echo "Généré par \`lab/snapshot.sh\` (docker inspect, docker images, docker exec). Lecture seule."
echo
echo "## Notes"
echo
echo "- KICKS : installation dans \`Guide_installation_KICKS_1.5.0_TK5_OculiX.pdf\`, conservation dans \`KICKS.md\`."
echo "- Image KICKS publique : \`ghcr.io/julienmerconsulting/target-mainframe-kicks:1.5.0-installed\` (MYLOGON inclus depuis le 14/09/2026) ; le compose la tire."
echo "- Filets locaux : \`oculix-lab/target-mainframe-kicks:kicks-installed-2026-08-27\` (avant MYLOGON) et \`...:kicks-installed-2026-09-14\`."
echo
echo "## Images"
echo
echo "| Image | ID | Taille | Créée |"
echo "|---|---|---|---|"
docker images --format "| {{.Repository}}:{{.Tag}} | {{.ID}} | {{.Size}} | {{.CreatedAt}} |" | grep -E "mainframe|runner|oculix-lab"
echo
echo "## Conteneurs"
echo
for c in target-mainframe target-mainframe-kicks oculix-runner; do
  docker inspect "$c" > /dev/null 2>&1 || { echo "### $c : absent"; echo; continue; }
  echo "### $c"
  echo
  docker inspect "$c" --format '- image : `{{.Config.Image}}`
- créé : {{.Created}}
- état : {{.State.Status}} (exit {{.State.ExitCode}})
- restart : {{.HostConfig.RestartPolicy.Name}} · mémoire : {{.HostConfig.Memory}} · nano-cpus : {{.HostConfig.NanoCpus}}
- ports : {{range $k, $v := .HostConfig.PortBindings}}{{$k}}→{{range $v}}{{.HostPort}}{{end}} {{end}}
- entrypoint : `{{.Config.Entrypoint}}`
- réseaux : {{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}
- montages : {{len .Mounts}}'
  echo "- env :"
  docker inspect "$c" --format '{{range .Config.Env}}{{printf "  - `%s`\n" .}}{{end}}' | grep -v 'PATH='
  echo
done
echo "## Réseau"
echo
docker network inspect oculix-mainframe-lab_lab-net --format '- `{{.Name}}` · driver {{.Driver}} · {{range .IPAM.Config}}{{.Subnet}}{{end}}' 2>/dev/null || echo "- réseau lab-net absent"
echo
echo "## Dans le runner"
echo
if docker exec oculix-runner true 2>/dev/null; then
  echo '```'
  docker exec oculix-runner sh -c 'java -version 2>&1 | head -1; echo; sha256sum /opt/oculix/*.jar; echo; dpkg -l xvfb xauth libxtst6 libxi6 2>/dev/null | grep ^ii | awk "{print \$2, \$3}"'
  echo '```'
else
  echo "- oculix-runner arrêté, contenu non lu"
fi
