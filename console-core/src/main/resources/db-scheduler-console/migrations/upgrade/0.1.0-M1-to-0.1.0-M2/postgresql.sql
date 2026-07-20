alter table dsc_execution_history add column task_data bytea;
alter table dsc_execution_history add column task_data_type varchar(512);
create index dsc_eh_finished_idx on dsc_execution_history (finished_at);

create table dsc_failed_execution (
  task_name text not null,
  task_instance text not null,
  task_data bytea,
  task_data_type varchar(512),
  priority smallint not null,
  failure_count integer not null,
  failed_at timestamp with time zone not null,
  picked_by varchar(255),
  exception_class varchar(512),
  exception_message varchar(2000),
  stacktrace text,
  primary key (task_name, task_instance)
);

create index dsc_fe_failed_at_idx on dsc_failed_execution (failed_at);
