create table pocoma_read.pot_projection_snapshots (
    artifact_id uuid primary key,
    projection_type varchar(64) not null,
    pipeline_id varchar(128) not null,
    pipeline_version integer not null check (pipeline_version >= 1),
    pot_id uuid not null,
    pot_version bigint not null check (pot_version >= 1),
    status varchar(16) not null check (status in ('ACTIVE','DELETED')),
    label varchar(255) not null check (label <> ''),
    creator_id uuid not null,
    unique (projection_type,pipeline_id,pipeline_version,pot_id,pot_version),
    foreign key (artifact_id) references pocoma_read.projection_artifacts(artifact_id) deferrable initially deferred
);

create table pocoma_read.pot_projection_shareholders (
    artifact_id uuid not null references pocoma_read.pot_projection_snapshots(artifact_id) on delete restrict,
    shareholder_id uuid not null,
    ordinal integer not null check (ordinal >= 0),
    name varchar(255) not null check (name <> ''),
    weight_numerator bigint not null check (weight_numerator >= 0),
    weight_denominator bigint not null check (weight_denominator > 0),
    user_id uuid,
    deleted boolean not null,
    primary key (artifact_id,shareholder_id), unique (artifact_id,ordinal)
);

create table pocoma_read.pot_projection_expenses (
    artifact_id uuid not null references pocoma_read.pot_projection_snapshots(artifact_id) on delete restrict,
    expense_id uuid not null,
    ordinal integer not null check (ordinal >= 0),
    payer_id uuid not null,
    amount_numerator bigint not null check (amount_numerator >= 0),
    amount_denominator bigint not null check (amount_denominator > 0),
    label varchar(255) not null check (label <> ''),
    deleted boolean not null,
    primary key (artifact_id,expense_id), unique (artifact_id,ordinal),
    foreign key (artifact_id,payer_id) references pocoma_read.pot_projection_shareholders(artifact_id,shareholder_id)
);

create table pocoma_read.pot_projection_expense_shares (
    artifact_id uuid not null,
    expense_id uuid not null,
    shareholder_id uuid not null,
    ordinal integer not null check (ordinal >= 0),
    weight_numerator bigint not null check (weight_numerator >= 0),
    weight_denominator bigint not null check (weight_denominator > 0),
    primary key (artifact_id,expense_id,shareholder_id), unique (artifact_id,expense_id,ordinal),
    foreign key (artifact_id,expense_id) references pocoma_read.pot_projection_expenses(artifact_id,expense_id),
    foreign key (artifact_id,shareholder_id) references pocoma_read.pot_projection_shareholders(artifact_id,shareholder_id)
);
