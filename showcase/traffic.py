"""Demo traffic for the showcase: keeps two banks and the clearing house busy, so the dashboards, traces and alerts have
something to show.

Uses only the public REST APIs, like the UIs do:
- the back office of each bank opens a few accounts, takes cash deposits, closes one account and decides approvals;
- customers send transfers in online banking: to accounts of their own bank (booked internally) and to the other bank
  (SEPA Instant through the clearing house), now and then to the other bank's closed account (rejected with AC04) or
  to an account that doesn't exist there (AC01), and now and then a transfer that needs an approval;
- chaos phases at the clearing house, one after another, with normal traffic in between: creditor banks too slow,
  half the forwards lost, bank B cut off, the clearing house down. Each one fires alerts that resolve once it is over.

Standard library only; runs in the python image of the compose profile "showcase". Settings via environment:
BANK_A_URL (http://bank-a:8080), BANK_B_URL (http://bank-b:8081), CLEARING_URL (http://clearing:8090),
ACCOUNTS per bank (5), PAUSE_SECONDS (1.5), CHAOS (on; off to skip the phases), CHAOS_START_MINUTES (10),
CHAOS_PAUSE_MINUTES (15, normal traffic between two phases).
"""

import concurrent.futures
import json
import os
import random
import signal
import sys
import threading
import time
import urllib.error
import urllib.request

BANK_URLS = {
    "bank-a": os.environ.get("BANK_A_URL", "http://bank-a:8080").rstrip("/"),
    "bank-b": os.environ.get("BANK_B_URL", "http://bank-b:8081").rstrip("/"),
}
CLEARING_URL = os.environ.get("CLEARING_URL", "http://clearing:8090").rstrip("/")
ACCOUNTS = int(os.environ.get("ACCOUNTS", "5"))
PAUSE_SECONDS = float(os.environ.get("PAUSE_SECONDS", "1.5"))
CHAOS = os.environ.get("CHAOS", "on") != "off"
CHAOS_START_MINUTES = float(os.environ.get("CHAOS_START_MINUTES", "10"))
CHAOS_PAUSE_MINUTES = float(os.environ.get("CHAOS_PAUSE_MINUTES", "15"))

APPROVAL_LIMIT_CENTS = 10_000_00  # transfers from 10,000 € need an approval (bank.risk.review-threshold-cents)
NORMAL = {"forwardDelayMs": 0, "dropForwardRate": 0.0, "outage": False, "unreachableBanks": []}


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


def call(base, method, path, body=None):
    """One API call; returns (status, json or None). Network errors count as status 0."""
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(base + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode()
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        return error.code, None
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        return 0, None


class Bank:
    def __init__(self, name, url):
        self.name, self.url = name, url
        self.accounts = []  # {"id", "iban"}
        self.closed = None  # IBAN of a closed account
        self.code = None  # bank code, from the first IBAN

    def call(self, method, path, body=None):
        return call(self.url, method, path, body)

    def open_account(self, deposit_cents):
        """The back office opens the account and takes a cash deposit at the counter."""
        status, body = self.call("POST", "/api/backoffice/accounts", {"overdraftLimitCents": random.choice([0, 0, 200_00])})
        if status != 201:
            return None
        self.code = body["iban"][4:12]
        if deposit_cents:
            self.deposit(body["id"], deposit_cents)
        return {"id": body["id"], "iban": body["iban"]}

    def deposit(self, account_id, cents):
        self.call("POST", f"/api/backoffice/accounts/{account_id}/deposits", {"amountCents": cents})

    def setup(self):
        self.accounts = [a for a in (self.open_account(random.randint(1_000, 8_000) * 100) for _ in range(ACCOUNTS)) if a]
        closing = self.open_account(0)
        if closing and self.call("POST", f"/api/backoffice/accounts/{closing['id']}/close")[0] == 200:
            self.closed = closing["iban"]
        log(f"{self.name}: {len(self.accounts)} accounts opened{', one closed' if self.closed else ''}")

    def transfer(self, debtor, creditor_iban, cents):
        """A customer in online banking: money to an IBAN, of this bank (internal) or another one (SEPA Instant)."""
        status, answer = self.call("POST", f"/api/customer/accounts/{debtor['id']}/transfers",
                                   {"creditorIban": creditor_iban, "amountCents": cents})
        return answer["transferId"] if status == 202 else None

    def decide_later(self, transfer_id):
        """An approver who takes a few seconds and approves most transfers."""
        def decide():
            time.sleep(random.uniform(5, 20))
            approved = random.random() < 0.7
            status, _ = self.call("POST", f"/api/backoffice/transfers/{transfer_id}/approval", {"approved": approved, "comment": "showcase"})
            log(f"{self.name}: approval {transfer_id[:8]} {'approved' if approved else 'denied'} ({status})")
        threading.Thread(target=decide, daemon=True).start()


def iban(bank_code, account_number):
    """A German IBAN with valid check digits (ISO 13616: mod 97), for an account that doesn't exist."""
    bban = f"{bank_code}{account_number:010d}"
    digits = int(bban + "131400")  # D = 13, E = 14, check digits 00
    return f"DE{98 - digits % 97:02d}{bban}"


def wait_until_up():
    for name, url in [*BANK_URLS.items(), ("clearing", CLEARING_URL)]:
        path = "/api/ui-config" if name != "clearing" else "/api/clearing/chaos"
        while call(url, "GET", path)[0] != 200:
            log(f"waiting for {name} …")
            time.sleep(3)


# ---------- chaos at the clearing house ----------

def set_chaos(settings):
    status, _ = call(CLEARING_URL, "PUT", "/api/clearing/chaos", {**NORMAL, **settings})
    return status == 200


def chaos_phases(bank_b):
    """One phase after another, with normal traffic in between. Every phase is undone afterwards."""
    phases = [
        ("creditor banks too slow: forwards 12 s late, after the deadline (AB05)", {"forwardDelayMs": 12_000}, 4),
        ("half the forwards lost: the creditor bank never sees them (AB05)", {"dropForwardRate": 0.5}, 4),
        ("bank B cut off: the clearing house can't reach it", {"unreachableBanks": [bank_b.code]}, 4),
        ("clearing house down: refuses all messages; payments go under investigation after 5 minutes", {"outage": True}, 7),
    ]
    time.sleep(CHAOS_START_MINUTES * 60)
    while True:
        for description, settings, minutes in phases:
            if set_chaos(settings):
                log(f"chaos for {minutes} minutes: {description}")
                time.sleep(minutes * 60)
                set_chaos(NORMAL)
                log(f"chaos over; normal for {CHAOS_PAUSE_MINUTES:g} minutes")
            time.sleep(CHAOS_PAUSE_MINUTES * 60)


# ---------- everyday traffic ----------

def step(banks, pool):
    bank = random.choice(banks)
    other = next(b for b in banks if b is not bank)
    debtor, creditor = random.sample(bank.accounts, 2)
    roll = random.random()
    if roll < 0.40:
        transfer_id = bank.transfer(debtor, creditor["iban"], cents=random.randint(5, 300) * 100)
        log(f"{bank.name}: internal transfer {(transfer_id or 'refused')[:8]}")
    elif roll < 0.65:
        transfer_id = bank.transfer(debtor, random.choice(other.accounts)["iban"], cents=random.randint(5, 300) * 100)
        log(f"{bank.name} → {other.name}: payment {(transfer_id or 'refused')[:8]}")
    elif roll < 0.67 and other.closed:
        transfer_id = bank.transfer(debtor, other.closed, cents=random.randint(5, 50) * 100)
        log(f"{bank.name} → {other.name}: payment {(transfer_id or 'refused')[:8]} to a closed account (AC04)")
    elif roll < 0.68 and other.code:
        transfer_id = bank.transfer(debtor, iban(other.code, random.randint(9_000_000_000, 9_999_999_999)), cents=10_00)
        log(f"{bank.name} → {other.name}: payment {(transfer_id or 'refused')[:8]} to an unknown account (AC01)")
    elif roll < 0.88:
        bank.deposit(debtor["id"], random.randint(50, 1_000) * 100)
        log(f"{bank.name}: deposit")
    elif roll < 0.92:
        bank.deposit(debtor["id"], APPROVAL_LIMIT_CENTS + 5_000_00)
        transfer_id = bank.transfer(debtor, creditor["iban"], cents=APPROVAL_LIMIT_CENTS + random.randint(0, 4_000) * 100)
        if transfer_id:
            log(f"{bank.name}: transfer {transfer_id[:8]} above the approval limit")
            bank.decide_later(transfer_id)
    elif roll < 0.94:
        list(pool.map(lambda _: bank.transfer(debtor, creditor["iban"], cents=random.randint(1, 50) * 100), range(10)))
        log(f"{bank.name}: burst of 10 concurrent transfers")


def main():
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))  # docker compose stop: undo the chaos on the way out
    wait_until_up()
    set_chaos(NORMAL)  # a phase may have been running when the generator stopped last time
    banks = [Bank(name, url) for name, url in BANK_URLS.items()]
    for bank in banks:
        bank.setup()
    if any(len(bank.accounts) < 2 for bank in banks):
        sys.exit("not enough accounts opened")

    if CHAOS:
        log(f"chaos phases start in {CHAOS_START_MINUTES:g} minutes")
        threading.Thread(target=chaos_phases, args=(banks[1],), daemon=True).start()
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=10)
    try:
        while True:
            step(banks, pool)
            time.sleep(random.uniform(0.5, 2) * PAUSE_SECONDS)
    finally:
        set_chaos(NORMAL)


if __name__ == "__main__":
    main()
