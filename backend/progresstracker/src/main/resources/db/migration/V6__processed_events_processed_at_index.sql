-- The worker purges processed_events rows once they are old enough that no redelivery can still
-- arrive. It selects them by processed_at, which without this index means reading the whole table.
create index idx_processed_events_processed_at on processed_events (processed_at);
