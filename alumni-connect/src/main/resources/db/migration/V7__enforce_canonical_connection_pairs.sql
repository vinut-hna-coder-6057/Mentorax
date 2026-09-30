ALTER TABLE connections
    ADD COLUMN pair_low_id BIGINT GENERATED ALWAYS AS (LEAST(requester_id, receiver_id)) STORED,
    ADD COLUMN pair_high_id BIGINT GENERATED ALWAYS AS (GREATEST(requester_id, receiver_id)) STORED;

CREATE UNIQUE INDEX uk_connection_canonical_pair
    ON connections (pair_low_id, pair_high_id);
