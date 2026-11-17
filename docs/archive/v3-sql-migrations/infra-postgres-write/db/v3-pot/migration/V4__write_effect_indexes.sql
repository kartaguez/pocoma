-- Performance only: exact candidate effects in publication and deferred version_has_effect.
-- No constraint or historical scan is moved into SQL.
create index v3_pot_header_effect on v3_pot_header(pot_id,active_from);
create index v3_pot_details_effect on v3_pot_details(pot_id,active_from);
create index v3_shareholder_header_effect on v3_shareholder_header(pot_id,active_from);
create index v3_shareholder_details_effect on v3_shareholder_details(pot_id,active_from);
create index v3_shareholder_weight_effect on v3_shareholder_weight(pot_id,active_from);
create index v3_expense_header_effect on v3_expense_header(pot_id,active_from);
create index v3_expense_details_effect on v3_expense_details(pot_id,active_from);
create index v3_expense_financials_effect on v3_expense_financials(pot_id,active_from);
