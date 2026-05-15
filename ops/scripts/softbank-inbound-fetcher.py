#!/usr/bin/env python3
"""
SoftBank IMAP inbound-mail fetcher (multi-account).

Polls imap.softbank.ne.jp every 2 minutes (via systemd timer), fetches
unseen messages for each configured i.softbank.jp address, parses the raw
MIME, and forwards each mail as a JSON payload to the CRM inbound webhook
at http://127.0.0.1:50000/api/inbound/receive-raw.

UID state is persisted per-account in STATE_DIR so already-processed
messages are skipped even if the IMAP \\Seen flag doesn't stick.

IMAP password NOTE:
  SoftBank separates SMTP credentials from the IMAP mail password.
  The IMAP password is the "接続用パスワード (メールパスワード)" issued
  from the My SoftBank portal — different from the SMTP password used
  in the AMG carrier pool.
"""

import imaplib
import ssl
import email
import email.header
import json
import urllib.request
import os
import sys
import re

# --- Configuration -------------------------------------------------------
IMAP_HOST    = "imap.softbank.jp"
IMAP_PORT    = 993
MAILBOX      = "INBOX"
WEBHOOK_URL  = "http://127.0.0.1:50000/api/inbound/receive-raw"
STATE_DIR    = "/var/lib/softbank-inbound-fetcher"
MAX_FETCH    = 50  # per-account safety cap per run

# Accounts are loaded from SOFTBANK_IMAP_ACCOUNTS env var.
# Format: "user1@i.softbank.jp|password1;user2@i.softbank.jp|password2;..."
# The env var itself is injected by systemd via EnvironmentFile=/etc/softbank-fetcher.env (0600 root:root).
def _load_accounts():
    raw = os.environ.get("SOFTBANK_IMAP_ACCOUNTS", "")
    if not raw:
        return []
    out = []
    for pair in raw.split(";"):
        pair = pair.strip()
        if not pair:
            continue
        if "|" not in pair:
            print(f"WARN: malformed SOFTBANK_IMAP_ACCOUNTS entry (no '|'): {pair[:30]}", file=sys.stderr)
            continue
        user, pw = pair.split("|", 1)
        out.append((user.strip(), pw.strip()))
    return out

ACCOUNTS = _load_accounts()
# -------------------------------------------------------------------------


def state_path_for(account):
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", account)
    return os.path.join(STATE_DIR, f"processed_uids_{safe}.txt")


def load_seen_uids(account):
    p = state_path_for(account)
    if not os.path.exists(p):
        return set()
    with open(p, "r") as f:
        return {line.strip() for line in f if line.strip()}


def save_uid(account, uid_str):
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(state_path_for(account), "a") as f:
        f.write(uid_str + "\n")


def decode_header_value(raw):
    if raw is None:
        return ""
    parts = email.header.decode_header(raw)
    out = []
    for byt, charset in parts:
        if isinstance(byt, bytes):
            out.append(byt.decode(charset or "utf-8", errors="replace"))
        else:
            out.append(byt)
    return "".join(out)


def extract_text_body(msg):
    if msg.is_multipart():
        for part in msg.walk():
            if part.get_content_type() == "text/plain":
                payload = part.get_payload(decode=True)
                if payload:
                    charset = part.get_content_charset() or "utf-8"
                    return payload.decode(charset, errors="replace")
        for part in msg.walk():
            if part.get_content_type() == "text/html":
                payload = part.get_payload(decode=True)
                if payload:
                    charset = part.get_content_charset() or "utf-8"
                    html = payload.decode(charset, errors="replace")
                    return re.sub(r"<[^>]+>", "", html.replace("<br", "\n<br"))
    else:
        payload = msg.get_payload(decode=True)
        if payload:
            charset = msg.get_content_charset() or "utf-8"
            return payload.decode(charset, errors="replace")
    return ""


def parse_mime(raw_bytes):
    msg = email.message_from_bytes(raw_bytes)
    from_val = decode_header_value(msg.get("From", ""))
    to_val   = decode_header_value(msg.get("To",   ""))
    subj     = decode_header_value(msg.get("Subject", ""))
    msg_id   = (msg.get("Message-ID") or "").strip()
    body     = extract_text_body(msg)
    try:
        raw_str = raw_bytes.decode("utf-8", errors="replace")
    except Exception:
        raw_str = raw_bytes.decode("latin-1", errors="replace")
    return {
        "from":      from_val,
        "to":        to_val,
        "subject":   subj,
        "body":      body,
        "raw":       raw_str[:65535],
        "messageId": msg_id,
    }


def post_to_crm(payload_dict):
    data = json.dumps(payload_dict).encode("utf-8")
    req = urllib.request.Request(
        WEBHOOK_URL,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.status, resp.read().decode("utf-8", errors="replace")


def run_account(user, password):
    seen_uids = load_seen_uids(user)

    ctx = ssl.create_default_context()
    try:
        conn = imaplib.IMAP4_SSL(IMAP_HOST, IMAP_PORT, ssl_context=ctx)
    except Exception as e:
        print(f"ERROR[{user}]: cannot connect: {e}", file=sys.stderr)
        return 0, 0

    try:
        try:
            typ, data = conn.login(user, password)
            if typ != "OK":
                print(f"ERROR[{user}]: IMAP login failed: {data}", file=sys.stderr)
                return 0, 0
        except imaplib.IMAP4.error as e:
            print(f"ERROR[{user}]: IMAP login error: {e}", file=sys.stderr)
            return 0, 0

        conn.select(MAILBOX)
        typ, data = conn.uid("search", None, "UNSEEN")
        if typ != "OK" or not data[0]:
            return 0, 0
        uid_list = data[0].split()
        new_uids = [u for u in uid_list if u.decode() not in seen_uids]
        if not new_uids:
            return 0, 0

        processed = 0
        for uid in new_uids[:MAX_FETCH]:
            uid_str = uid.decode()
            try:
                typ, msg_data = conn.uid("fetch", uid, "(RFC822)")
                if typ != "OK" or not msg_data or not msg_data[0]:
                    print(f"WARN[{user}]: could not fetch UID {uid_str}", file=sys.stderr)
                    continue
                raw = msg_data[0][1]
                if not isinstance(raw, bytes):
                    continue
                payload = parse_mime(raw)
                status, body = post_to_crm(payload)
                if status == 200:
                    save_uid(user, uid_str)
                    processed += 1
                    accepted = '"accepted":true' in body or '"accepted": true' in body
                    if accepted:
                        print(f"OK[{user}]: UID {uid_str} accepted (from={payload['from'][:60]})")
                    else:
                        print(f"OK(rejected)[{user}]: UID {uid_str} — {body[:120]}")
                else:
                    print(f"WARN[{user}]: CRM returned {status} for UID {uid_str}: {body[:120]}", file=sys.stderr)
            except Exception as e:
                print(f"ERROR[{user}]: processing UID {uid_str}: {e}", file=sys.stderr)
        return processed, len(new_uids)
    finally:
        try:
            conn.logout()
        except Exception:
            pass


def main():
    if not ACCOUNTS:
        print("ERROR: SOFTBANK_IMAP_ACCOUNTS env var is empty or missing — "
              "load via EnvironmentFile=/etc/softbank-fetcher.env", file=sys.stderr)
        sys.exit(1)

    total_processed = 0
    total_new = 0
    for user, password in ACCOUNTS:
        if not password:
            print(f"SKIP[{user}]: no IMAP password set", file=sys.stderr)
            continue
        p, n = run_account(user, password)
        total_processed += p
        total_new += n
    if total_processed > 0 or total_new > 0:
        print(f"Done: {total_processed}/{total_new} new messages forwarded to CRM across {len(ACCOUNTS)} accounts")


if __name__ == "__main__":
    main()
