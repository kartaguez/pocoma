alter table consumption_slots
    add column terminal_reason text;

update consumption_slots slot
set terminal_reason = coalesce(
        (
            select nullif(btrim(claim.failure_category), '')
            from consumption_claims claim
            where claim.slot_id = slot.slot_id
              and claim.end_reason = 'PROCESSING_FAILURE'
            order by claim.attempt_number desc
            limit 1
        ),
        'LEGACY_FAILURE_REASON_UNAVAILABLE'
    )
where slot.status = 'DONE' and slot.terminal_outcome = 'FAILED';

update consumption_slots
set terminal_reason = 'LEGACY_REJECTION_REASON_UNAVAILABLE'
where status = 'DONE' and terminal_outcome = 'REJECTED';

update consumption_slots
set terminal_reason = 'LEGACY_ABANDON_REASON_UNAVAILABLE'
where status = 'DONE' and terminal_outcome = 'ABANDONED';

alter table consumption_slots
    add constraint ck_consumption_slots_terminal_reason_code
        check (terminal_reason is null or btrim(terminal_reason) <> ''),
    add constraint ck_consumption_slots_terminal_reason_lifecycle check (
        (status = 'PENDING' and terminal_reason is null)
        or
        (status = 'DONE' and (
            (terminal_outcome = 'SUCCESS' and terminal_reason is null)
            or
            (terminal_outcome in ('REJECTED', 'FAILED', 'ABANDONED') and terminal_reason is not null)
        ))
    );
