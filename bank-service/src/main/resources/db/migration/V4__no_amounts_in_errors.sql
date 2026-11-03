-- Error messages end up in the logs (Postgres and the bank-service, both in Loki): no amounts in them.
-- The transfer ID is enough to find the postings.
CREATE OR REPLACE FUNCTION check_transfer_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (SELECT COALESCE(SUM(amount_cents), 0) FROM postings WHERE transfer_id = NEW.transfer_id) <> 0 THEN
        RAISE EXCEPTION 'transfer % is unbalanced', NEW.transfer_id;
    END IF;
    RETURN NULL;
END
$$;
