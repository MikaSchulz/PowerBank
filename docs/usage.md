# Using the bank

## UI

Open **http://localhost:8080** once the app is running. The UI is a plain HTML/JS page served by the bank-service
(`bank-service/src/main/resources/static`), no build step.

- **Accounts:** open accounts (each gets an IBAN), deposit, block and unblock, see every posting with the running
  balance. The header shows which bank the UI belongs to (bank A or bank B, see [local setup](local-setup.md)).
  Nicknames like "Alice" only live in your browser (localStorage): the bank itself only knows pseudonymous IDs.
- **Transfers:** internal, to another bank (SEPA Instant through the clearing house), or as a cash deposit. Each one
  opens a drawer with its business
  status, an explanation of what Temporal is doing right now, its history and postings in the ledger and a link to the
  Temporal UI.
- **Approvals:** transfers above the limit show up in a banner; approve or deny them via the `approve` update.
- **Scenarios:** one click each: above the approval limit, insufficient funds, the clearing house rejects (a bank that
  isn't a participant: `RC01`, refund), duplicate submission (409), and a burst of 10 concurrent transfers that shows
  the row locks at work.
- **Ledger health:** the header shows the sum of all postings. With double-entry bookkeeping it must always be zero.

## Manual test checklist

1. **Quick start** creates Alice (1,000 €, 200 € overdraft) and Bob (250 €).
2. Send a normal internal transfer and watch it go from *Received* to *Booked*.
3. Run every scenario once and check the result in the drawer and the ledger health badge.
4. With the clearing house running ([local setup](local-setup.md#the-clearing-house)), send an external transfer: it
   goes *Reserved* → *At clearing house* → *Refunded* with the clearing house's reason (`RC01` for a bank outside it;
   until bank B answers payments, #60, `AB05` for bank B).
5. Stop the clearing house and send an external transfer: the amount stays *Reserved*, the workflow asks with status
   inquiries, and after 5 minutes the transfer is *Under investigation*. Start the clearing house again: at the next
   inquiry (every 10 minutes) it learns the clearing house never got the payment and refunds it.
6. Kill the app (`Ctrl+C`) while an external transfer waits for its confirmation, start it again: the workflow
   continues where it stopped.
7. Block Alice, try to send money from her account (rejected), unblock her again.

## API with curl

```bash
# Open an account (no application process until stage 7) and deposit 1,000 €
curl -s -X POST localhost:8080/api/accounts -H 'Content-Type: application/json' -d '{}'
curl -s -X POST localhost:8080/api/accounts/<account>/deposits -H 'Content-Type: application/json' -d '{"amountCents":100000}'

# Internal transfer; the transferId protects against duplicate submission (second POST → 409)
curl -s -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
  -d '{"transferId":"<uuid>","debtorAccountId":"<account>","creditorAccountId":"<account2>","amountCents":2500}'

# Transfer to another bank: SEPA Instant through the clearing house (localhost:8090)
curl -s -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
  -d '{"debtorAccountId":"<account>","creditorIban":"DE89 3704 0044 0532 0130 00","amountCents":4000}'

# Status (query) and approval on suspected fraud (update): from 10,000 € internally, 5,000 € to external banks
curl -s localhost:8080/api/transfers/<transferId>
curl -s -X POST localhost:8080/api/transfers/<transferId>/approval -H 'Content-Type: application/json' -d '{"approved":true}'

# Balance and postings
curl -s localhost:8080/api/accounts/<account>
curl -s localhost:8080/api/accounts/<account>/postings
```

More endpoints:

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/accounts` | all accounts with balances (customer and system) |
| `POST` | `/api/accounts/{id}/block`, `/unblock` | block or unblock an account |
| `GET` | `/api/transfers?accountId=&limit=` | transfers, newest first, with their business status from the ledger |
| `GET` | `/api/transfers/{id}/postings` | the postings a transfer created |
| `GET` | `/api/transfers/{id}/history` | its business history: every status with time and reason |
| `GET` | `/api/ledger/summary` | sum of all postings (must be 0), counts |
| `POST` | `/api/clearing/inbound/pacs002` | for the clearing house: the final status of a payment this bank sent |
