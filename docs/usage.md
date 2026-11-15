# Using the bank

## UI

Open **http://localhost:8080** once the app is running. Plain HTML and ES modules served by the bank-service
(`bank-service/src/main/resources/static`), no build step. Three areas, each with its own page and its own part of
the API (#61); the header shows which bank you are in (bank A or bank B, see [local setup](local-setup.md)).

| Area | For | What it shows |
| --- | --- | --- |
| **Online banking** (`customer.html`) | a customer | one account with its IBAN, balance and bookings; money to any IBAN (this bank's: booked internally, another bank's: SEPA Instant); every transfer in and out; one-click scenarios |
| **Back office** (`backoffice.html`) | bank employees | the work queues (approvals, payments under investigation), customer accounts (open, cash deposit, block, unblock, close), every transfer with its postings |
| **Operations** (`operations.html`) | admins | the ledger check, the bank's own accounts (cash, clearing, settlement), the clearing house's chaos settings (late or lost forwards, down, a bank it can't reach) and settlement accounts, links to Grafana and Temporal |

- **No login yet** ([#36](https://github.com/MikaSchulz/PowerBank/issues/36)): online banking has an *Acting as* picker
  instead, one customer per account until account opening ([#13](https://github.com/MikaSchulz/PowerBank/issues/13)).
  The back office links to "online banking as this customer".
- **Nicknames** like "Alice" only live in your browser (localStorage): the bank itself only knows pseudonymous IDs.
- **Every transfer** opens a drawer with its business status, an explanation of what Temporal is doing right now and its
  history, with links to the Temporal UI and the trace in Grafana. The back office also sees its postings on every
  account; a customer doesn't see the bank's own bookings.
- **Scenarios** in online banking, one click each, from the account to the IBAN in the form: above the approval limit
  (the back office decides), insufficient funds, the clearing house rejects (a bank that isn't a participant: `RC01`,
  refund), duplicate submission (409), and a burst of 10 concurrent transfers that shows the row locks at work.

## Manual test checklist

1. **Back office → Quick start** opens Alice (1,000 €, 200 € overdraft) and Bob (250 €).
2. **Online banking**, acting as Alice: send money to Bob's IBAN (it is suggested) and watch it go from *Received* to
   *Booked*. Act as Bob: the transfer is there as incoming.
3. Run every scenario once and check the result in the drawer; decide the approval in the **back office**; the ledger
   check in **operations** stays at Σ 0.
4. With bank B and the clearing house running ([local setup](local-setup.md#the-clearing-house)), send money to an IBAN
   of bank B: in bank A it goes *Reserved* → *At clearing house* → *Completed*, in bank B's back office the incoming
   payment *Received* → *Accepted* → *Booked*. To an IBAN bank B doesn't have, both sides end rejected with `AC01`; to a
   bank outside the clearing house with `RC01`.
5. In **operations**, set the clearing house to *Creditor bank too slow* and send money to bank B: rejected with `AB05`
   after the deadline. The same with bank B ticked under *Can't reach*. Set it back to *Normal*.
6. Stop the clearing house (or set it to *Clearing house down*) and send money to bank B: the amount stays
   *Reserved*, the workflow asks with status inquiries, and after 5 minutes the transfer is *Under investigation* in
   the back office's queue. Start the clearing house again: at the next inquiry (every 10 minutes) it learns the
   clearing house never got the payment and refunds it.
7. Kill the app (`Ctrl+C`) while a transfer waits for its confirmation, start it again: the workflow continues where it
   stopped.
8. Block Alice in the back office, try to send money from her account (rejected), unblock her again.
9. Open an account in bank B and close it again (*Close*, only with a zero balance). Money sent to it from bank A is
   rejected with `AC04`.

## API with curl

The API is grouped by area like the UI, so authorization can be added later without moving an endpoint.

```bash
# Back office: open an account (no application process until stage 7) and take 1,000 € in cash
curl -s -X POST localhost:8080/api/backoffice/accounts -H 'Content-Type: application/json' -d '{}'
curl -s -X POST localhost:8080/api/backoffice/accounts/<account>/deposits -H 'Content-Type: application/json' -d '{"amountCents":100000}'

# Online banking: money to an IBAN. One of this bank's: booked internally; another bank's: SEPA Instant through the
# clearing house (localhost:8090). The transferId protects against submitting twice (second POST → 409).
curl -s -X POST localhost:8080/api/customer/accounts/<account>/transfers -H 'Content-Type: application/json' \
  -d '{"transferId":"<uuid>","creditorIban":"DE89 3704 0044 0532 0130 00","amountCents":4000}'

# Online banking: the account, its transfers and one transfer's status and history
curl -s localhost:8080/api/customer/accounts/<account>
curl -s localhost:8080/api/customer/accounts/<account>/transfers
curl -s localhost:8080/api/customer/accounts/<account>/transfers/<transferId>/history

# Back office: approve a suspicious transfer (from 10,000 € internally, 5,000 € to other banks)
curl -s -X POST localhost:8080/api/backoffice/transfers/<transferId>/approval -H 'Content-Type: application/json' -d '{"approved":true}'

# Operations: the ledger check
curl -s localhost:8080/api/operations/ledger/summary
```

More endpoints:

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/customer/accounts/{id}/postings` | the account's bookings |
| `GET` | `/api/backoffice/accounts`, `/{id}`, `/{id}/postings` | customer accounts with balances, one of them, its postings |
| `POST` | `/api/backoffice/accounts/{id}/block`, `/unblock` | block or unblock an account |
| `POST` | `/api/backoffice/accounts/{id}/close` | close an account for good, only with a zero balance (409 otherwise) |
| `GET` | `/api/backoffice/transfers?accountId=&status=&limit=` | every transfer, newest first; by account, or by status for the work queues |
| `GET` | `/api/backoffice/transfers/{id}`, `/history`, `/postings` | a transfer's status, history and postings |
| `GET` | `/api/operations/system-accounts`, `/{id}/postings` | the bank's own accounts and their postings |
| `GET`, `PUT` | `/api/operations/clearing-house/chaos` | the clearing house's chaos settings, through the bank: `forwardDelayMs`, `dropForwardRate`, `outage`, `unreachableBanks` (bank codes) |
| `GET` | `/api/operations/clearing-house/settlement-accounts` | the participants' settlement accounts at the clearing house |
| `POST` | `/api/clearing/inbound/pacs008` | for the clearing house: a payment from another bank |
| `POST` | `/api/clearing/inbound/pacs002` | for the clearing house: the final status of a payment, sent or received |
