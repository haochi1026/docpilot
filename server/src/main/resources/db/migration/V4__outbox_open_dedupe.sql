-- The previous INSERT ... WHERE NOT EXISTS implementation acquired broad gap
-- locks under MySQL REPEATABLE READ and deadlocked concurrent document uploads.
-- A generated key makes the open-event invariant an atomic database constraint;
-- terminal rows evaluate to NULL, so historical events remain append-only.
ALTER TABLE outbox_message
  ADD COLUMN open_dedupe_key VARCHAR(190)
    GENERATED ALWAYS AS (
      CASE
        WHEN status IN ('PENDING','RETRY','SENDING')
          THEN CONCAT(event_type, ':', aggregate_id)
        ELSE NULL
      END
    ) STORED,
  ADD UNIQUE KEY uk_outbox_open_dedupe(open_dedupe_key);
