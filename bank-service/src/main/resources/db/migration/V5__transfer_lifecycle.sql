-- The business lifecycle of a transfer lives in the ledger (transfer decider, decision 0007): the status names of the
-- decider, and the history of every status change.

UPDATE transfers SET status = 'RECEIVED' WHERE status = 'PENDING';
UPDATE transfers SET status = 'SENT_TO_EXTERNAL_BANK' WHERE status = 'DEBITED';

ALTER TABLE transfers
    ADD CONSTRAINT transfers_status_check CHECK (status IN (
        'RECEIVED', 'AWAITING_APPROVAL', 'APPROVED', 'SENT_TO_EXTERNAL_BANK', 'BOOKED', 'COMPLETED', 'REFUNDED', 'REJECTED'
    ));

-- The events of a transfer after it was received (the receipt itself is the transfers row): its business history.
-- status: the status the event led to; reason: why it waits, is rejected or refunded, or the approver's comment.
-- Never names, IBANs or amounts.
CREATE TABLE transfer_events (
    id          BIGSERIAL PRIMARY KEY,
    transfer_id UUID        NOT NULL REFERENCES transfers,
    type        TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    reason      TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX transfer_events_transfer_idx ON transfer_events (transfer_id, id);

-- History is immutable, like the postings.
CREATE FUNCTION reject_transfer_event_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'transfer events are append-only';
END
$$;

CREATE TRIGGER transfer_events_append_only
    BEFORE UPDATE OR DELETE ON transfer_events
    FOR EACH ROW EXECUTE FUNCTION reject_transfer_event_change();

-- Transfers from before: their current status as one event, so the decider sees the same state as before.
INSERT INTO transfer_events (transfer_id, type, status, reason, occurred_at)
SELECT id,
       CASE status
           WHEN 'BOOKED' THEN 'TransferBooked'
           WHEN 'SENT_TO_EXTERNAL_BANK' THEN 'TransferSentToExternalBank'
           WHEN 'COMPLETED' THEN 'TransferCompleted'
           WHEN 'REFUNDED' THEN 'TransferRefunded'
           WHEN 'REJECTED' THEN 'TransferRejected'
       END,
       status,
       reason,
       updated_at
FROM transfers
WHERE status <> 'RECEIVED';
