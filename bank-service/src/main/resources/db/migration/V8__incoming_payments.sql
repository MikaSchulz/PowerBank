-- Incoming SEPA Instant payments (#60): a transfer of kind INCOMING, from the settlement account to the customer.
-- ACCEPTED: the bank answered yes and waits for the clearing house's settlement. reason_code: the ISO 20022 reason
-- code the bank answered a rejected incoming payment with (AC01, AC04, AC06).

ALTER TABLE transfers DROP CONSTRAINT transfers_status_check;

ALTER TABLE transfers
    ADD CONSTRAINT transfers_status_check CHECK (status IN (
        'RECEIVED', 'AWAITING_APPROVAL', 'APPROVED', 'ACCEPTED', 'RESERVED', 'SENT_TO_EXTERNAL_BANK', 'UNDER_INVESTIGATION',
        'BOOKED', 'COMPLETED', 'REFUNDED', 'REJECTED'
    ));

ALTER TABLE transfers ADD COLUMN reason_code TEXT;
ALTER TABLE transfer_events ADD COLUMN reason_code TEXT;
