-- Ledger: double-entry bookkeeping. The ledger only knows IDs; personal data is stored encrypted
-- in customers and accounts (from stage 9 on).

CREATE TABLE customers (
    id          UUID PRIMARY KEY,
    key_id      TEXT NOT NULL,   -- the customer's key in the key store (crypto-shredding)
    name_enc    BYTEA,
    address_enc BYTEA
);

CREATE TABLE accounts (
    id                    UUID PRIMARY KEY,
    customer_id           UUID REFERENCES customers,            -- NULL for system accounts
    iban_enc              BYTEA,
    status                TEXT   NOT NULL CHECK (status IN ('OPEN', 'BLOCKED', 'CLOSED')),
    kind                  TEXT   NOT NULL DEFAULT 'CUSTOMER' CHECK (kind IN ('CUSTOMER', 'SYSTEM')),
    overdraft_limit_cents BIGINT NOT NULL DEFAULT 0 CHECK (overdraft_limit_cents >= 0)
);

CREATE TABLE transfers (
    id         UUID PRIMARY KEY,                 -- = transfer ID = part of the workflow ID
    status     TEXT        NOT NULL,             -- PENDING, BOOKED, DEBITED, COMPLETED, REFUNDED, REJECTED
    reason     TEXT,                             -- rejection reason
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id           BIGSERIAL PRIMARY KEY,
    transfer_id  UUID        NOT NULL REFERENCES transfers,
    account_id   UUID        NOT NULL REFERENCES accounts,
    leg          TEXT        NOT NULL CHECK (leg IN ('debit', 'credit', 'refund')),
    amount_cents BIGINT      NOT NULL CHECK (amount_cents <> 0), -- debits negative, credits positive
    booked_at    TIMESTAMPTZ NOT NULL,
    UNIQUE (transfer_id, account_id, leg)                        -- never the same posting twice
);

CREATE INDEX postings_account_idx ON postings (account_id, id);

-- Postings are immutable: corrections are new postings.
CREATE FUNCTION reject_posting_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'postings are append-only';
END
$$;

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION reject_posting_change();

-- Double-entry: at the end of every transaction, the postings of a transfer sum up to zero.
CREATE FUNCTION check_transfer_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    total BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount_cents), 0) INTO total FROM postings WHERE transfer_id = NEW.transfer_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'transfer % is unbalanced: % cents', NEW.transfer_id, total;
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transfer_balanced();

-- The bank's system accounts
INSERT INTO accounts (id, status, kind) VALUES
    ('00000000-0000-0000-0000-000000000001', 'OPEN', 'SYSTEM'),  -- cash: deposits and withdrawals
    ('00000000-0000-0000-0000-000000000002', 'OPEN', 'SYSTEM');  -- clearing account for external banks
