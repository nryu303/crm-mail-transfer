#!/bin/bash
# Install the updated softbank-inbound-fetcher.py with bounce-drain + MAX_FETCH=50.
# Operator runs this once; subsequent timer ticks pick up the new behavior.
#
# Why: 2026-05-28 incident — hh.88798087@gmail.com → ycxaieika@i.softbank.jp took
# 2 hours to surface in the CRM because the IMAP UNSEEN queue had 670 Postmaster
# bounces ahead of the real reply, and MAX_FETCH=10 only drained the queue at
# 300/hour. New behavior pre-flags bounces as \Seen at IMAP level (no body fetch
# = fast metadata op) so real user replies don't queue behind them.
#
# Safe to re-run: the previous file is backed up to *.bak.<timestamp>.

set -euo pipefail

SRC=/home/centos/crm-platform/deploy/softbank-inbound-fetcher.py
DST=/usr/local/bin/softbank-inbound-fetcher.py

if [[ ! -f "$SRC" ]]; then
    echo "ERROR: $SRC not found"
    exit 1
fi

python3 -m py_compile "$SRC" || { echo "ERROR: syntax check failed"; exit 1; }

sudo cp -p "$DST" "${DST}.bak.$(date +%Y%m%d-%H%M%S)"
sudo install -o root -g centos -m 0750 "$SRC" "$DST"

echo "Installed. Backup: $(ls -t ${DST}.bak.* | head -1)"
echo
echo "Trigger one immediate run to drain the backlog:"
echo "  sudo systemctl start softbank-inbound-fetcher.service"
echo "Then watch:"
echo "  sudo journalctl -u softbank-inbound-fetcher --since '1 minute ago' | grep -E 'drain_bounces|Done:'"
