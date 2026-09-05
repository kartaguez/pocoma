create table external_identities (
    issuer text not null,
    subject text not null,
    pocoma_user_id uuid not null,
    primary key (issuer, subject),
    constraint ck_external_identities_issuer check (btrim(issuer) <> ''),
    constraint ck_external_identities_subject check (btrim(subject) <> '')
);
