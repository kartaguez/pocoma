create schema if not exists pocoma_control;

create table pocoma_control.pipeline_version_activations (
    pipeline_id varchar(255) not null,
    pipeline_version integer not null,
    activated_at timestamp with time zone not null,
    primary key (pipeline_id, pipeline_version),
    constraint ck_pipeline_version_activation_version check (pipeline_version >= 1)
);

create table pocoma_control.projection_serving_selections (
    projection_type varchar(255) not null,
    pipeline_id varchar(255) not null,
    pipeline_version integer not null,
    selected_at timestamp with time zone not null,
    primary key (projection_type),
    constraint fk_projection_serving_activation foreign key (pipeline_id, pipeline_version)
        references pocoma_control.pipeline_version_activations (pipeline_id, pipeline_version)
        on delete restrict
);

insert into pocoma_control.pipeline_version_activations (pipeline_id, pipeline_version, activated_at)
values
    ('read-pot', 1, current_timestamp),
    ('balance-projection', 2, current_timestamp)
on conflict do nothing;

insert into pocoma_control.projection_serving_selections
    (projection_type, pipeline_id, pipeline_version, selected_at)
values
    ('READ_POT', 'read-pot', 1, current_timestamp),
    ('POT_BALANCES', 'balance-projection', 2, current_timestamp)
on conflict do nothing;
