"""Demo traffic for the showcase: keeps the bank busy so the dashboards, traces and alerts have something to show.

Uses only the public REST API, like the UI does: opens a few accounts, then sends internal transfers and deposits, now
and then a transfer that needs an approval (approved or denied a little later) and a burst of concurrent transfers.

No transfers to other banks for now: they go through the clearing house since #59, and until bank B answers it (#60)
every one of them would be rejected. They come back with #62, together with the clearing house's chaos settings.

Standard library only; runs in the python image of the compose profile "showcase". Settings via environment:
BANK_URL (default http://bank-service:8080), ACCOUNTS (6), PAUSE_SECONDS (1.5).
"""

import concurrent.futures
import json
import os
import random
import threading
import time
import urllib.error
import urllib.request
import uuid

BANK_URL = os.environ.get("BANK_URL", "http://bank-service:8080").rstrip("/")
ACCOUNTS = int(os.environ.get("ACCOUNTS", "6"))
PAUSE_SECONDS = float(os.environ.get("PAUSE_SECONDS", "1.5"))
APPROVAL_LIMIT_CENTS = 10_000_00  # transfers from 10,000 € need an approval (bank.risk.review-threshold-cents)


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


def call(method, path, body=None):
    """One API call; returns (status, json or None). Network errors count as status 0."""
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BANK_URL + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode()
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        return error.code, None
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        return 0, None


def wait_for_bank():
    while call("GET", "/api/ui-config")[0] != 200:
        log(f"waiting for {BANK_URL} …")
        time.sleep(3)


def open_account(deposit_cents):
    status, body = call("POST", "/api/accounts", {"overdraftLimitCents": random.choice([0, 0, 200_00])})
    if status != 201:
        return None
    account = body["id"]
    call("POST", f"/api/accounts/{account}/deposits", {"amountCents": deposit_cents})
    return account


def transfer(debtor, creditor, cents):
    status, answer = call("POST", "/api/transfers", {"debtorAccountId": debtor, "creditorAccountId": creditor, "amountCents": cents})
    return answer["transferId"] if status == 202 else None


def decide_later(transfer_id):
    """An approver who takes a few seconds and approves most transfers."""
    def decide():
        time.sleep(random.uniform(5, 20))
        approved = random.random() < 0.7
        status, _ = call("POST", f"/api/transfers/{transfer_id}/approval", {"approved": approved, "comment": "showcase"})
        log(f"approval {transfer_id[:8]}: {'approved' if approved else 'denied'} ({status})")
    threading.Thread(target=decide, daemon=True).start()


def main():
    wait_for_bank()
    accounts = [a for a in (open_account(random.randint(1_000, 8_000) * 100) for _ in range(ACCOUNTS)) if a]
    log(f"{len(accounts)} accounts opened")

    pool = concurrent.futures.ThreadPoolExecutor(max_workers=10)
    while True:
        debtor, creditor = random.sample(accounts, 2)
        roll = random.random()
        if roll < 0.65:
            transfer_id = transfer(debtor, creditor, cents=random.randint(5, 300) * 100)
            log(f"internal transfer {(transfer_id or 'refused')[:8]}")
        elif roll < 0.85:
            call("POST", f"/api/accounts/{debtor}/deposits", {"amountCents": random.randint(50, 1_000) * 100})
            log("deposit")
        elif roll < 0.90:
            call("POST", f"/api/accounts/{debtor}/deposits", {"amountCents": APPROVAL_LIMIT_CENTS + 5_000_00})
            transfer_id = transfer(debtor, creditor, cents=APPROVAL_LIMIT_CENTS + random.randint(0, 4_000) * 100)
            if transfer_id:
                log(f"transfer {transfer_id[:8]} above the approval limit")
                decide_later(transfer_id)
        elif roll < 0.92:
            list(pool.map(lambda _: transfer(debtor, creditor, cents=random.randint(1, 50) * 100), range(10)))
            log("burst of 10 concurrent transfers")
        time.sleep(random.uniform(0.5, 2) * PAUSE_SECONDS)


if __name__ == "__main__":
    main()
