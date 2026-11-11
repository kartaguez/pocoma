drop table pocoma_read.pot_projection_user_index;
drop table pocoma_read.pot_projection_expense_shares;
drop table pocoma_read.pot_projection_expenses;
drop table pocoma_read.pot_projection_shareholders;
drop table pocoma_read.pot_projection_snapshots;

drop table pocoma_read.projection_artifacts;
drop table pocoma_read.projection_failures;
drop table pocoma_read.projection_heads;
drop table pocoma_read.projection_invariant_violations;

drop trigger pot_version_metadata_immutable on pocoma_read.pot_version_metadata;
drop table pocoma_read.pot_version_metadata;
drop function pocoma_read.reject_pot_version_metadata_mutation();
