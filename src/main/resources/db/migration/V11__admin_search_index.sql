-- Phase 8: Admin search index for trace_id lookups
-- transaction_id already has UNIQUE constraint (serves as index)
-- external_reference already has idx_transaction_external_ref
-- trace_id has no standalone index — add for admin search performance
CREATE INDEX idx_transaction_trace_id ON main.transaction(trace_id);
