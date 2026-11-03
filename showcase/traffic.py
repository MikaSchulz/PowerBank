"""Demo traffic for the showcase: keeps the bank busy so the dashboards, traces and alerts have something to show.

Uses only the public REST API, like the UI does: opens a few accounts, then sends internal and external transfers and
deposits, now and then a transfer that needs an approval (approved or denied a little later) and a burst of
concurrent transfers. Every few minutes the external bank goes into a short "chaos" phase, so its alerts fire and
resolve again.

Standard library only; runs in the python image of the compose profile "showcase". Settings via environment:
BANK_URL (default http://bank-service:8080), ACCOUNTS (6), PAUSE_SECONDS (1.5), CHAOS_EVERY_MINUTES (20, 0 = never).
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
CHAOS_EVERY_MINUTES = float(os.environ.get("CHAOS_EVERY_MINUTES", "20"))
# The first chaos phase starts 5 minutes after the start, so the dashboards show normal traffic first. It lasts 4
# minutes: the refund alert looks at the last 15 minutes and needs that long to see more than 30 % refunds.
CHAOS_AFTER_MINUTES = 5
CHAOS_MINUTES = 4
REJECTED_IBAN_SHARE = 0.05
APPROVAL_LIMIT_CENTS = 10_000_00  # transfers from 10,000 € need an approval (bank.risk.review-threshold-cents)

# Normal: about 15 % of the external transfers get refunded, well below the alert threshold of 30 %. Chaos: most do.
DEFAULT_BANK = {"minDelayMs": 1000, "maxDelayMs": 4000, "rejectionRate": 0.1, "failureRate": 0.1}
CHAOS_BANK = {"minDelayMs": 500, "maxDelayMs": 3000, "rejectionRate": 0.8, "failureRate": 0.15}


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


def iban(rejected=False):
    """A German IBAN with valid check digits. The simulator always rejects IBANs ending in 000000."""
    account = "0" * 10 if rejected else "".join(random.choice("0123456789") for _ in range(10))
    if not rejected and account.endswith("000000"):
        account = account[:-1] + "1"
    bban = "37040044" + account
    check = 98 - int(bban + "131400") % 97  # "DE" = 13 14, then "00"
    return f"DE{check:02d}{bban}"


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


def transfer(debtor, creditor=None, external_iban=None, cents=None):
    body = {"debtorAccountId": debtor, "amountCents": cents}
    if external_iban:
        body["creditorIban"] = external_iban
    else:
        body["creditorAccountId"] = creditor
    status, answer = call("POST", "/api/transfers", body)
    return answer["transferId"] if status == 202 else None


def decide_later(transfer_id):
    """An approver who takes a few seconds and approves most transfers."""
    def decide():
        time.sleep(random.uniform(5, 20))
        approved = random.random() < 0.7
        status, _ = call("POST", f"/api/transfers/{transfer_id}/approval", {"approved": approved, "comment": "showcase"})
        log(f"approval {transfer_id[:8]}: {'approved' if approved else 'denied'} ({status})")
    threading.Thread(target=decide, daemon=True).start()


def set_external_bank(settings, name):
    status, _ = call("PUT", "/api/simulator/external-bank", settings)
    log(f"external bank: {name} ({status})")


def main():
    wait_for_bank()
    accounts = [a for a in (open_account(random.randint(1_000, 8_000) * 100) for _ in range(ACCOUNTS)) if a]
    log(f"{len(accounts)} accounts opened")
    set_external_bank(DEFAULT_BANK, "default")

    pool = concurrent.futures.ThreadPoolExecutor(max_workers=10)
    started = time.time()
    chaos = False
    while True:
        if CHAOS_EVERY_MINUTES > 0:
            minute = (time.time() - started) / 60 % CHAOS_EVERY_MINUTES
            in_chaos = CHAOS_AFTER_MINUTES <= minute < CHAOS_AFTER_MINUTES + CHAOS_MINUTES
            if in_chaos != chaos:
                chaos = in_chaos
                set_external_bank(CHAOS_BANK if chaos else DEFAULT_BANK, "chaos" if chaos else "default")

        debtor, creditor = random.sample(accounts, 2)
        roll = random.random()
        if roll < 0.50:
            transfer_id = transfer(debtor, creditor, cents=random.randint(5, 300) * 100)
            log(f"internal transfer {(transfer_id or 'refused')[:8]}")
        elif roll < 0.72:
            transfer_id = transfer(debtor, external_iban=iban(rejected=random.random() < REJECTED_IBAN_SHARE), cents=random.randint(20, 500) * 100)
            log(f"external transfer {(transfer_id or 'refused')[:8]}")
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
