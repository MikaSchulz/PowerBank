-- Bank identity (#57): every customer account has an IBAN of this bank (bank code from bank.identity.bank-code), and
-- a settlement account mirrors the bank's account at the clearing house.

CREATE SEQUENCE account_number_seq START WITH 1000000001;

-- Personal data: never in logs or spans; encrypted with stage 9 (#14), then looked up through a hash.
ALTER TABLE accounts ADD COLUMN iban TEXT UNIQUE;

-- Existing customer accounts get the next account numbers. German IBAN: DE, check digits (98 - mod 97 of the BBAN
-- followed by "DE00" as digits, 131400), bank code, 10-digit account number.
UPDATE accounts a
SET iban = 'DE' || lpad((98 - ((n.bban || '131400')::numeric % 97))::text, 2, '0') || n.bban
FROM (SELECT id, '${bankCode}' || lpad(nextval('account_number_seq')::text, 10, '0') AS bban
      FROM accounts WHERE kind = 'CUSTOMER') n
WHERE a.id = n.id;

ALTER TABLE accounts
    ADD CONSTRAINT accounts_iban_check CHECK ((kind = 'CUSTOMER') = (iban IS NOT NULL));

INSERT INTO accounts (id, status, kind) VALUES
    ('00000000-0000-0000-0000-000000000003', 'OPEN', 'SYSTEM');  -- settlement account at the central bank (clearing house)
