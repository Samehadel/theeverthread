#!/usr/bin/env bash
# Run only on the dedicated monitoring EC2: sudo bash prepare-host.sh
set -euo pipefail

fail() { echo "Error: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail 'Run this script with sudo.'
[[ $(uname -s) == Linux ]] || fail 'This script requires Ubuntu Linux.'
# shellcheck disable=SC1091
source /etc/os-release
[[ ${ID:-} == ubuntu && ${VERSION_ID:-} == 24.04 ]] || fail 'Expected Ubuntu 24.04.'

# Do not replace another installation or uninstall packages from an existing host.
for package in docker.io docker-compose docker-compose-v2 podman-docker containerd runc; do
    if dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -qx 'install ok installed'; then
        fail "Conflicting package $package is installed; review the host before continuing."
    fi
done

prepare_swap() {
    if [[ $(swapon --show --noheadings | wc -l) -gt 0 ]]; then
        echo 'Existing active swap found; preserving it.'
        return
    fi
    local swap_file=/swapfile-monitoring
    if [[ -e $swap_file || -L $swap_file ]]; then
        fail "$swap_file already exists but is inactive; inspect it before continuing."
    fi
    local available_kib
    available_kib=$(df --output=avail / | tail -1 | tr -d ' ')
    [[ $available_kib -ge 4194304 ]] || fail 'Need at least 4 GiB free before allocating swap.'
    (umask 077; fallocate -l 2G "$swap_file")
    chmod 600 "$swap_file"
    mkswap "$swap_file"
    swapon "$swap_file"
    if ! awk -v target="$swap_file" '$1 == target { found=1 } END { exit !found }' /etc/fstab; then
        printf '\n%s none swap sw 0 0\n' "$swap_file" >> /etc/fstab
    fi
}

install_docker() {
    if command -v docker >/dev/null 2>&1; then
        docker compose version >/dev/null || fail 'Existing Docker lacks Compose; review installation first.'
        echo 'Existing Docker and Compose found; preserving installed versions.'
        return
    fi
    apt-get update
    apt-get install -y ca-certificates curl
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
    apt-get update
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
}

prepare_swap
install_docker
systemctl enable --now docker
docker version
docker compose version
swapon --show
free -h
df -h /
echo 'Host prepared. Monitoring services have not been started.'
