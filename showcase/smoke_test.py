"""Smoke test for the showcase (CI, or by hand once `docker compose --profile showcase up` is running).

Waits until the demo traffic has moved money both ways between bank A and bank B, then checks:
- both ledgers and the clearing house's settlement accounts are balanced (the postings sum up to 0);
- one payment's trace spans bank A, the clearing house and bank B (read from Tempo through Grafana).

Standard library only. Settings via environment: BANK_A_URL (http://localhost:8080), BANK_B_URL
(http://localhost:8081), CLEARING_URL (http://localhost:8090), GRAFANA_URL (http://localhost:3000), TIMEOUT_SECONDS (180).
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request

BANK_A = os.environ.get("BANK_A_URL", "http://localhost:8080")
BANK_B = os.environ.get("BANK_B_URL", "http://localhost:8081")
CLEARING = os.environ.get("CLEARING_URL", "http://localhost:8090")
GRAFANA = os.environ.get("GRAFANA_URL", "http://localhost:3000")
TIMEOUT_SECONDS = int(os.environ.get("TIMEOUT_SECONDS", "180"))


def get(url):
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read().decode())
    except (urllib.error.URLError, TimeoutError, ConnectionError, json.JSONDecodeError):
        return None


def transfers(bank, status):
    return get(f"{bank}/api/backoffice/transfers?status={status}&limit=200") or []


def eventually(what, probe):
    deadline = time.monotonic() + TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        result = probe()
        if result:
            print(f"ok: {what}")
            return result
        time.sleep(5)
    sys.exit(f"FAILED: {what} (after {TIMEOUT_SECONDS} s)")


def services_in_trace(trace_id):
    """The service names of all spans of a trace, from Tempo (through Grafana's data source proxy)."""
    trace = get(f"{GRAFANA}/api/datasources/proxy/uid/tempo/api/traces/{trace_id}") or {}
    names = set()
    for batch in trace.get("batches", []) + trace.get("resourceSpans", []):
        for attribute in batch.get("resource", {}).get("attributes", []):
            if attribute["key"] == "service.name":
                names.add(attribute["value"].get("stringValue"))
    return names


def main():
    paid = {}
    for debtor, creditor, name in [(BANK_A, BANK_B, "A → B"), (BANK_B, BANK_A, "B → A")]:
        sent = eventually(f"a payment {name} completed at the debtor bank",
                          lambda d=debtor: [t for t in transfers(d, "COMPLETED") if t["kind"] == "EXTERNAL"])
        eventually(f"a payment {name} booked at the creditor bank",
                   lambda c=creditor: [t for t in transfers(c, "BOOKED") if t["kind"] == "INCOMING"])
        paid[name] = sent[0]

    for name, url in [("bank A", BANK_A), ("bank B", BANK_B)]:
        summary = get(f"{url}/api/operations/ledger/summary")
        print(name, summary)
        if summary is None or summary["sumOfAllPostingsCents"] != 0:
            sys.exit(f"FAILED: the ledger of {name} is unbalanced")
    settlement = get(f"{CLEARING}/api/clearing/settlement-accounts")
    print("clearing house", settlement)
    if settlement is None or sum(a["balanceCents"] for a in settlement) != 0:
        sys.exit("FAILED: the clearing house's settlement accounts don't add up")

    trace_id = paid["A → B"]["traceId"]
    expected = {"bank-a", "clearing", "bank-b"}
    eventually(f"trace {trace_id} spans {', '.join(sorted(expected))}", lambda: expected <= services_in_trace(trace_id))


if __name__ == "__main__":
    main()
