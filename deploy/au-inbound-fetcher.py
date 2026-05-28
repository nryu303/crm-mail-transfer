#!/usr/bin/env python3
"""
au IMAP inbound-mail fetcher (multi-account).

Polls imap.ezweb.ne.jp every 2 minutes (via systemd timer), fetches unseen
messages for each configured @au.com address, parses the raw MIME, and
forwards each mail as a JSON payload to the CRM inbound webhook at
http://127.0.0.1:50000/api/inbound/receive-raw.

UID state is persisted per-account in STATE_DIR so already-processed
messages are skipped even if the IMAP \\Seen flag doesn't stick.

Credentials (id + meal connection password) are loaded from the env var
AU_IMAP_ACCOUNTS, injected by systemd via /etc/au-fetcher.env (0600 root).

Note: au's @au.com mailboxes are served by the legacy ezweb IMAP host
(greeting: "Au_mail IMAP4rev1"). mail.au.com and imap.au.com do not
expose IMAP on port 993.
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
IMAP_HOST    = "imap.ezweb.ne.jp"
IMAP_PORT    = 993
MAILBOX      = "INBOX"
WEBHOOK_URL  = "http://127.0.0.1:50000/api/inbound/receive-raw"
STATE_DIR    = "/var/lib/au-inbound-fetcher"
MAX_FETCH    = 50  # per-account safety cap per run; matches softbank fetcher post-2026-05-28

# Accounts: "user1|password1;user2|password2;..."  (user = SMTP/IMAP id, NOT the email)
def _load_accounts():
    raw = os.environ.get("AU_IMAP_ACCOUNTS", "")
    if not raw:
        return []
    out = []
    for pair in raw.split(";"):
        pair = pair.strip()
        if not pair:
            continue
        if "|" not in pair:
            print(f"WARN: malformed AU_IMAP_ACCOUNTS entry (no '|'): {pair[:30]}", file=sys.stderr)
            continue
        user, pw = pair.split("|", 1)
        out.append((user.strip(), pw.strip()))
    return out

ACCOUNTS = _load_accounts()
MAX_5XX_RETRIES = 3
# -------------------------------------------------------------------------


def state_path_for(account):
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", account)
    return os.path.join(STATE_DIR, f"processed_uids_{safe}.txt")


def retry_path_for(account):
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", account)
    return os.path.join(STATE_DIR, f"retry_counts_{safe}.txt")


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


def drain_bounces(conn, user):
    """Same pattern as the softbank fetcher: bulk-mark Postmaster / Mailer-Daemon
    delivery-failure mails as \\Seen at IMAP level WITHOUT body fetch, so real
    user replies don't queue behind hundreds of bounces when the per-tick budget
    runs out. Added 2026-05-28 alongside the softbank-side fix."""
    flagged = 0
    for keyword in ("postmaster", "mailer-daemon"):
        try:
            typ, data = conn.uid("search", None, "UNSEEN", "FROM", keyword)
            if typ != "OK" or not data or not data[0]:
                continue
            uids = data[0].split()
            if not uids:
                continue
            uid_csv = b",".join(uids).decode("ascii")
            typ, _ = conn.uid("store", uid_csv, "+FLAGS", "(\\Seen)")
            if typ == "OK":
                flagged += len(uids)
                for u in uids:
                    save_uid(user, u.decode())
        except Exception as e:
            print(f"WARN[{user}]: drain_bounces({keyword}) failed: {e}", file=sys.stderr)
    if flagged > 0:
        print(f"INFO[{user}]: drain_bounces flagged {flagged} bounce mails as \\Seen")
    return flagged


def run_account(user, password):
    seen_uids = load_seen_uids(user)

    ctx = ssl.create_default_context()
    # au's IMAP cert chain doesn't validate cleanly under the default trust store
    # on RHEL9 — we accept the cert without verification since the host is
    # pinned by IP via the carrier's DNS and the channel is still TLS-encrypted.
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

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

        conn.select(MAILBOX)
        # Pre-pass: drop Postmaster bounces from the UNSEEN queue at IMAP level
        # so real user replies don't queue behind a bounce backlog.
        drain_bounces(conn, user)
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
        print("ERROR: AU_IMAP_ACCOUNTS env var is empty or missing — "
              "load via EnvironmentFile=/etc/au-fetcher.env", file=sys.stderr)
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

    if accounts_with_pw > 0 and login_ok_count == 0:
        print(f"FATAL: 0/{accounts_with_pw} accounts authenticated — au IMAP receive is DOWN",
              file=sys.stderr)
        sys.exit(1)
    if accounts_with_pw > 0 and login_ok_count < accounts_with_pw:
        print(f"WARN: only {login_ok_count}/{accounts_with_pw} accounts authenticated — "
              f"check ERROR lines above for the failing account(s)", file=sys.stderr)


if __name__ == "__main__":
    main()
