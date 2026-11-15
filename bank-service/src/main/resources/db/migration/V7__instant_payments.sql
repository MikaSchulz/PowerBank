-- SEPA Instant (#59): an external transfer reserves the amount, sends the payment to the clearing house and waits for
-- its final status; without one it stays under investigation.

ALTER TABLE transfers DROP CONSTRAINT transfers_status_check;

ALTER TABLE transfers
    ADD CONSTRAINT transfers_status_check CHECK (status IN (
        'RECEIVED', 'AWAITING_APPROVAL', 'APPROVED', 'RESERVED', 'SENT_TO_EXTERNAL_BANK', 'UNDER_INVESTIGATION',
        'BOOKED', 'COMPLETED', 'REFUNDED', 'REJECTED'
    ));
