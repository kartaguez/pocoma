-- Indexed containment for direct historical reads; no extension or integrity assumption.
-- Existing partial unique indexes serve open/current intervals.
create index v3_pot_header_interval on v3_pot_header using gist (int8range(active_from,active_to,'[)'));
create index v3_pot_details_interval on v3_pot_details using gist (int8range(active_from,active_to,'[)'));
create index v3_shareholder_header_interval on v3_shareholder_header using gist (int8range(active_from,active_to,'[)'));
create index v3_shareholder_details_interval on v3_shareholder_details using gist (int8range(active_from,active_to,'[)'));
create index v3_shareholder_weight_interval on v3_shareholder_weight using gist (int8range(active_from,active_to,'[)'));
create index v3_expense_header_interval on v3_expense_header using gist (int8range(active_from,active_to,'[)'));
create index v3_expense_details_interval on v3_expense_details using gist (int8range(active_from,active_to,'[)'));
create index v3_expense_financials_interval on v3_expense_financials using gist (int8range(active_from,active_to,'[)'));
