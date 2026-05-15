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


def retry_path_for(account):
    """Per-account retry counter file. Format: <uid>\t<count>\n. Used by H5 to
    dead-letter after MAX_5XX_RETRIES so a stuck CRM doesn't loop forever."""
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", account)
    return os.path.join(STATE_DIR, f"retry_counts_{safe}.txt")


# H5: dead-letter threshold. After this many consecutive CRM 5xx (or other non-200)
# responses for the same UID we treat it as permanently failed: save the UID to
# processed_uids so it won't be re-fetched on the next timer tick. Prevents the
# 2-minute loop spam when CRM is misconfigured.
MAX_5XX_RETRIES = 3


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


def load_retry_counts(account):
    p = retry_path_for(account)
    if not os.path.exists(p):
        return {}
    out = {}
    with open(p, "r") as f:
        for line in f:
            parts = line.strip().split("\t")
            if len(parts) == 2 and parts[1].isdigit():
                out[parts[0]] = int(parts[1])
    return out


def save_retry_counts(account, counts):
    os.makedirs(STATE_DIR, exist_ok=True)
    p = retry_path_for(account)
    tmp = p + ".tmp"
    with open(tmp, "w") as f:
        for uid, n in counts.items():
            f.write(f"{uid}\t{n}\n")
    os.replace(tmp, p)


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
    """Returns (processed, new_total, login_ok) where login_ok=False means we could
    not authenticate (network or credentials). Used by main() to distinguish
    partial failure (one bad account) from total outage (all accounts down)."""
    seen_uids = load_seen_uids(user)

    ctx = ssl.create_default_context()
    try:
        conn = imaplib.IMAP4_SSL(IMAP_HOST, IMAP_PORT, ssl_context=ctx)
    except Exception as e:
        print(f"ERROR[{user}]: cannot connect: {e}", file=sys.stderr)
        return 0, 0, False

    try:
        try:
            typ, data = conn.login(user, password)
            if typ != "OK":
                print(f"ERROR[{user}]: IMAP login failed: {data}", file=sys.stderr)
                return 0, 0, False
        except imaplib.IMAP4.error as e:
            print(f"ERROR[{user}]: IMAP login error: {e}", file=sys.stderr)
            return 0, 0, False

        # Per-account telemetry — always emitted on success so the journal can
        # distinguish "logged in, no new mail" from "exited before login attempt".
        # H4 (5/16) relies on this to alert on real outages without false positives.
        conn.select(MAILBOX)
        typ, data = conn.uid("search", None, "UNSEEN")
        if typ != "OK" or not data[0]:
            print(f"INFO[{user}]: logged in, 0 unseen")
            return 0, 0, True
        uid_list = data[0].split()
        new_uids = [u for u in uid_list if u.decode() not in seen_uids]
        print(f"INFO[{user}]: logged in, {len(uid_list)} unseen, {len(new_uids)} new")
        if not new_uids:
            return 0, 0, True

        retry_counts = load_retry_counts(user)
        retry_dirty = False
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
                    if uid_str in retry_counts:
                        del retry_counts[uid_str]
                        retry_dirty = True
                    accepted = '"accepted":true' in body or '"accepted": true' in body
                    if accepted:
                        print(f"OK[{user}]: UID {uid_str} accepted (from={payload['from'][:60]})")
                    else:
                        print(f"OK(rejected)[{user}]: UID {uid_str} — {body[:120]}")
                else:
                    # H5: count consecutive non-200 responses per UID; dead-letter at threshold.
                    n = retry_counts.get(uid_str, 0) + 1
                    retry_counts[uid_str] = n
                    retry_dirty = True
                    if n >= MAX_5XX_RETRIES:
                        save_uid(user, uid_str)
                        del retry_counts[uid_str]
                        print(f"DEAD-LETTER[{user}]: UID {uid_str} after {n} failed POSTs "
                              f"(last status={status}, body={body[:120]}) — skipped from now on",
                              file=sys.stderr)
                    else:
                        print(f"WARN[{user}]: CRM returned {status} for UID {uid_str} "
                              f"(attempt {n}/{MAX_5XX_RETRIES}): {body[:120]}", file=sys.stderr)
            except Exception as e:
                print(f"ERROR[{user}]: processing UID {uid_str}: {e}", file=sys.stderr)
        if retry_dirty:
            save_retry_counts(user, retry_counts)
        return processed, len(new_uids), True
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
    accounts_with_pw = 0
    login_ok_count = 0
    for user, password in ACCOUNTS:
        if not password:
            print(f"SKIP[{user}]: no IMAP password set", file=sys.stderr)
            continue
        accounts_with_pw += 1
        p, n, login_ok = run_account(user, password)
        total_processed += p
        total_new += n
        if login_ok:
            login_ok_count += 1
    if total_processed > 0 or total_new > 0:
        print(f"Done: {total_processed}/{total_new} new messages forwarded to CRM across {len(ACCOUNTS)} accounts")

    # H4: total-outage alert. Exit non-zero ONLY when every account with a configured
    # password failed to authenticate — that's the signal an operator needs to chase
    # (network/IMAP host down, all passwords invalidated). Partial failure (one bad
    # account out of N) exits 0 so the systemd timer doesn't go red on a single
    # mis-configured row.
    if accounts_with_pw > 0 and login_ok_count == 0:
        print(f"FATAL: 0/{accounts_with_pw} accounts authenticated — SoftBank IMAP receive is DOWN",
              file=sys.stderr)
        sys.exit(1)
    if accounts_with_pw > 0 and login_ok_count < accounts_with_pw:
        print(f"WARN: only {login_ok_count}/{accounts_with_pw} accounts authenticated — "
              f"check ERROR lines above for the failing account(s)", file=sys.stderr)


if __name__ == "__main__":
    main()
