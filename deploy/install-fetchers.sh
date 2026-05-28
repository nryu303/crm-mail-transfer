#!/bin/bash
# Install updated softbank + au inbound fetchers with drain_bounces() + MAX_FETCH=50.
# Replaces the older install-softbank-fetcher.sh. Backs up the previous scripts
# and installs both in one shot. Safe to re-run.
#
# Why: the 10-message-per-tick cap caused user-visible 2h delays when a pool
# mailbox built up Postmaster bounces. drain_bounces() pre-flags those as \Seen
# at IMAP level (metadata-only, fast) so real replies keep their place in line.

set -euo pipefail

install_one() {
    local src="$1"
    local dst="$2"
    if [[ ! -f "$src" ]]; then
        echo "skip: $src not present"
        return 0
    fi
    if ! python3 -m py_compile "$src"; then
        echo "ERROR: syntax check failed for $src"
        return 1
    fi
    if [[ -f "$dst" ]]; then
        sudo cp -p "$dst" "${dst}.bak.$(date +%Y%m%d-%H%M%S)"
    fi
    sudo install -o root -g centos -m 0750 "$src" "$dst"
    echo "installed: $dst"
}

install_one /home/centos/crm-platform/deploy/softbank-inbound-fetcher.py /usr/local/bin/softbank-inbound-fetcher.py
install_one /home/centos/crm-platform/deploy/au-inbound-fetcher.py       /usr/local/bin/au-inbound-fetcher.py

echo
echo "Trigger one immediate run to drain backlogs:"
echo "  sudo systemctl start softbank-inbound-fetcher.service"
echo "  sudo systemctl start au-inbound-fetcher.service"
echo "Then watch:"
echo "  sudo journalctl -u softbank-inbound-fetcher --since '2 minutes ago' | grep -E 'drain_bounces|Done:'"
