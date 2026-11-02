-- Transfer details for listings (UI, API). Filled when the transfer is accepted by the API.
-- No foreign keys on the accounts: a transfer to an unknown account is accepted first and rejected by the ledger.
ALTER TABLE transfers
    ADD COLUMN kind                TEXT,   -- INTERNAL, EXTERNAL, DEPOSIT
    ADD COLUMN debtor_account_id   UUID,
    ADD COLUMN creditor_account_id UUID,   -- NULL for transfers to external banks (the IBAN stays in the workflow)
    ADD COLUMN amount_cents        BIGINT;

CREATE INDEX transfers_created_idx ON transfers (created_at DESC);
CREATE INDEX transfers_debtor_idx ON transfers (debtor_account_id);
CREATE INDEX transfers_creditor_idx ON transfers (creditor_account_id);
