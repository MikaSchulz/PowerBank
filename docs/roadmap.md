# Roadmap

The stages of the original plan. Open work, including everything beyond them, is tracked in [GitHub issues](https://github.com/MikaSchulz/PowerBank/issues); the overview is [#2](https://github.com/MikaSchulz/PowerBank/issues/2).

- [x] **1. Warm-up:** killed the worker with `kill -9` in the middle of running external transfers. After the restart
      all of them finished and the balances were correct. (The official Java tutorial is left as your exercise.)
- [x] **2. Ledger without Temporal:** `LedgerTest` shows that booking twice changes nothing and all postings sum up to zero.
- [x] **3. Account decider:** sufficient funds, overdraft, blocked and closed accounts; Given/When/Then in `AccountDeciderTest`.
- [x] **4. Aggregate and internal transfer:** duplicate submission → 409; concurrent transfers never overdraw the
      account (tested in the ledger and through REST and workflows).
- [x] **5. External bank saga:** rejection and permanent unavailability lead to a refund, all balances add up.
- [ ] **6. Standing orders** (Temporal schedules), planned in [issue #1](https://github.com/MikaSchulz/PowerBank/issues/1)
- [ ] **7. Account opening with approval** (replaces `POST /api/accounts` and the placeholder customer)
- [x] **8. Suspected fraud:** the query shows `AWAITING_APPROVAL`; approval, denial and deadline expiry are tested,
      the deadline tests with time skipping.
- [ ] **9. Encryption** (payload codec, codec server; there's a spot for it in `TemporalConfig`)
- [ ] **10. Account closure and crypto-shredding** (`CloseAccount` already exists in the decider)
- [ ] **11. Failures and versioning**
- [ ] **12. Bonus: Quarkus worker** (`ExternalBankActivities` is already a separate interface for this)
