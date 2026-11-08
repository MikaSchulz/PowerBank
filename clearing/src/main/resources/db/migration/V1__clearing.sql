-- The clearing house: the participants' settlement accounts (central bank money) and the payments it clears.

-- Accounts: one per participant (bank:<bank code>), the reservations of payments in flight, and the central bank
-- that funds the participants. Double-entry: the postings of every reference sum up to zero.
CREATE TABLE settlement_accounts (
    id TEXT PRIMARY KEY
);

CREATE TABLE settlement_postings (
    id           BIGSERIAL PRIMARY KEY,
    reference    TEXT        NOT NULL,  -- payment UETR, or funding:<bank code>
    account_id   TEXT        NOT NULL REFERENCES settlement_accounts,
    leg          TEXT        NOT NULL,  -- reserve, settle, release, funding
    amount_cents BIGINT      NOT NULL CHECK (amount_cents <> 0),
    booked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (reference, account_id, leg)
);

CREATE FUNCTION reject_settlement_posting_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'settlement postings are append-only';
END
$$;

CREATE TRIGGER settlement_postings_append_only
    BEFORE UPDATE OR DELETE ON settlement_postings
    FOR EACH ROW EXECUTE FUNCTION reject_settlement_posting_change();

CREATE FUNCTION check_settlement_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (SELECT COALESCE(SUM(amount_cents), 0) FROM settlement_postings WHERE reference = NEW.reference) <> 0 THEN
        RAISE EXCEPTION 'settlement reference % is unbalanced', NEW.reference;
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER settlement_postings_balanced
    AFTER INSERT ON settlement_postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_settlement_balanced();

INSERT INTO settlement_accounts (id) VALUES ('central-bank'), ('reserved');

-- Payments: the current status and every change. No IBANs: they travel in the messages only.
CREATE TABLE payments (
    uetr          UUID PRIMARY KEY,
    status        TEXT        NOT NULL,
    reason_code   TEXT,
    debtor_bank   TEXT        NOT NULL,  -- bank codes
    creditor_bank TEXT,                  -- null if the creditor's bank is not a participant
    amount_cents  BIGINT      NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX payments_created_idx ON payments (created_at DESC);

CREATE TABLE payment_events (
    id          BIGSERIAL PRIMARY KEY,
    uetr        UUID        NOT NULL REFERENCES payments,
    type        TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    reason_code TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX payment_events_uetr_idx ON payment_events (uetr, id);
