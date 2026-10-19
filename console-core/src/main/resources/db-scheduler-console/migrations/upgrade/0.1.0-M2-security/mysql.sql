-- Additive indexes for existing M2 schemas. Apply once; do not also apply to fresh schemas.
-- Review lock/online-index options and scheduler-table indexes for your deployment.
create index dsc_eh_cursor_idx on dsc_execution_history (started_at, id);
create index dsc_eh_task_cursor_idx on dsc_execution_history (task_name, started_at, id);
create index dsc_eh_out_cursor_idx on dsc_execution_history (outcome, started_at, id);
create index dsc_fe_cursor_idx on dsc_failed_execution (failed_at, task_name, task_instance);
