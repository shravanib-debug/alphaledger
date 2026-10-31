-- Restartable, partitioned reconciliation staging.

-- Uncompressed out-of-line storage lets substring() fetch one slice without detoasting the whole file.
ALTER TABLE custodian_file ALTER COLUMN content SET STORAGE EXTERNAL;

-- File lines for the run in progress: the range-partitioned input of the staging step. Removed when the run completes;
-- custodian_file stays as the audit copy.
CREATE TABLE custodian_raw_row (
    run_id     BIGINT NOT NULL REFERENCES recon_run (id),
    row_number INT    NOT NULL,
    line       TEXT   NOT NULL,
    PRIMARY KEY (run_id, row_number)
);

-- A restarted step may re-write a chunk: each file row lands at most once.
ALTER TABLE custodian_record ADD CONSTRAINT uq_custodian_record_row UNIQUE (run_id, row_number);
ALTER TABLE custodian_record_error ADD CONSTRAINT uq_custodian_record_error_row UNIQUE (run_id, row_number);
