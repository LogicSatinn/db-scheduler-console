alter table dsc_execution_history add (
  task_data blob,
  task_data_type varchar2(512)
);
create index dsc_eh_finished_idx on dsc_execution_history (finished_at);

create table dsc_failed_execution (
  task_name varchar2(250) not null,
  task_instance varchar2(250) not null,
  task_data blob,
  task_data_type varchar2(512),
  priority number(5) not null,
  failure_count number(10) not null,
  failed_at timestamp(6) with time zone not null,
  picked_by varchar2(255),
  exception_class varchar2(512),
  exception_message varchar2(2000),
  stacktrace clob,
  primary key (task_name, task_instance)
);

create index dsc_fe_failed_at_idx on dsc_failed_execution (failed_at);
