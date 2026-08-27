#!/usr/bin/env bash
set -euo pipefail

EVIDENCE_DIR="${MUXTV_KVM_EVIDENCE:-.work/evidence/hosted-android/kvm}"
mkdir -p "$EVIDENCE_DIR"
LOG_PATH="$EVIDENCE_DIR/kvm-preflight.log"
exec > >(tee "$LOG_PATH") 2>&1

echo "runner_os=${RUNNER_OS:-unknown}"
echo "runner_arch=${RUNNER_ARCH:-unknown}"
uname -a

if [[ ! -c /dev/kvm ]]; then
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
  sudo udevadm control --reload-rules
  if ! sudo udevadm trigger --name-match=kvm; then
    echo "udevadm trigger returned non-zero; final /dev/kvm checks remain authoritative."
  fi
fi

if [[ -c /dev/kvm ]]; then
  sudo chmod 0666 /dev/kvm
fi

ls -l /dev/kvm 2>&1 || true
if [[ ! -c /dev/kvm ]]; then
  echo "GitHub-hosted runner did not expose /dev/kvm as a character device." >&2
  exit 1
fi
if [[ ! -r /dev/kvm ]]; then
  echo "GitHub-hosted runner /dev/kvm is not readable." >&2
  exit 1
fi
if [[ ! -w /dev/kvm ]]; then
  echo "GitHub-hosted runner /dev/kvm is not writable." >&2
  exit 1
fi

echo "Hosted Android KVM preflight passed."
