-- Server-assigned Pot, Shareholder, and Expense identities in READ command results (Phase B).

alter table read_command_result alter column pot_id drop not null;
alter table read_command_result add column created_resources jsonb;

alter table read_command_result add constraint read_command_result_pot_id_presence
    check ((operation = 'CreatePot' and write_outcome = 'REJECTED') or pot_id is not null);

alter table read_command_result add constraint read_command_result_created_resources_shape
    check (created_resources is null or (write_outcome = 'CHANGED' and jsonb_typeof(created_resources) = 'object'));
