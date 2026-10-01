-- Read-only preflight for the LatestKnownVersion Java/runtime rename.
-- SOURCE_VERSION_WATERMARK and the physical read-store names are intentionally retained.

select status, terminal_outcome, count(*) as slot_count
from consumption_slots
where consumer_type = 'SOURCE_VERSION_WATERMARK'
group by status, terminal_outcome
order by status, terminal_outcome;

select count(*) as events_without_latest_known_slot
from business_event_outbox event
left join consumption_slots slot
  on slot.consumable_type = 'EVENT'
 and slot.consumable_components = jsonb_build_array(event.id::text)
 and slot.consumer_type = 'SOURCE_VERSION_WATERMARK'
 and slot.consumer_components = '[]'::jsonb
where slot.slot_id is null;

select
  count(*) filter (where claim.lease_until >= current_timestamp) as active_claims,
  count(*) filter (where claim.lease_until < current_timestamp) as expired_claims
from consumption_slots slot
join consumption_claims claim on claim.claim_id = slot.current_claim_id
where slot.consumer_type = 'SOURCE_VERSION_WATERMARK'
  and slot.status = 'PENDING';

select count(*) as pots_with_events_without_initial_state
from (select distinct pot_id from business_event_outbox) event_pot
left join pocoma_read.source_version_watermarks state on state.pot_id = event_pot.pot_id
where state.pot_id is null;

with terminalized_versions as (
  select event.pot_id, max(input.subject_version) as max_terminalized_version
  from consumption_slots slot
  join consumption_inputs input on input.slot_id = slot.slot_id
  join business_event_outbox event on event.id::text = input.subject_id
  where slot.consumer_type = 'SOURCE_VERSION_WATERMARK'
    and slot.status = 'DONE'
    and slot.terminal_outcome = 'SUCCESS'
    and input.subject_type = 'EVENT'
  group by event.pot_id
)
select terminalized.pot_id, terminalized.max_terminalized_version, state.latest_version_seen
from terminalized_versions terminalized
left join pocoma_read.source_version_watermarks state on state.pot_id = terminalized.pot_id
where state.latest_version_seen is null
   or state.latest_version_seen < terminalized.max_terminalized_version
order by terminalized.pot_id;

with event_versions as (
  select pot_id, max(version) as max_event_version
  from business_event_outbox
  group by pot_id
)
select state.pot_id, state.latest_version_seen, event_versions.max_event_version
from pocoma_read.source_version_watermarks state
left join event_versions on event_versions.pot_id = state.pot_id
where event_versions.max_event_version is null
   or state.latest_version_seen > event_versions.max_event_version
order by state.pot_id;
