-- W3C trace ID of the request that accepted the transfer, so every transfer links to its trace in Tempo.
-- Not personal data; NULL when the request wasn't sampled.
ALTER TABLE transfers ADD COLUMN trace_id TEXT;
