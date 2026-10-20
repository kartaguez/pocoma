package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.kartaguez.pocoma.port.transaction.EventFamily;

/** Test-only INSERTs into all real CURRENT sources, including their real FK parents. */
final class EventSequencingSchemaFixture {
    static final String[] POSITIONS = {"business_event_positions", "command_terminal_event_positions",
            "binding_fact_positions", "user_created_fact_positions"};
    static final String[] SOURCES = {"business_event_outbox", "command_terminal_events",
            "external_identity_binding_facts", "user_created_facts"};
    static void insert(JdbcTemplate jdbc, EventFamily family, UUID id) {
        switch (family) {
            case BUSINESS_EVENT -> jdbc.update("""
                insert into business_event_outbox(id,event_type,pot_id,pot_partition_hash,aggregate_id,version,
                  payload_json,status,attempt_count,created_at) values (?,'POT_CREATED',?,0,?,1,'{}','PENDING',0,now())
                """, id,id,id);
            case COMMAND_TERMINAL_EVENT -> {
                command(jdbc,id);
                jdbc.update("insert into command_outcomes(command_id,outcome_type,public_code,resolved_at) values (?,'REJECTED','TEST',now())",id);
                jdbc.update("insert into command_terminal_events(event_id,event_type,command_id,command_partition_hash,recorded_at) values (?,'COMMAND_REJECTED',?,0,now())",id,id);
            }
            case BINDING_FACT -> {
                jdbc.update("insert into users values (?)",id);
                jdbc.update("insert into external_identity_binding_streams values ('step4c',?,1)",id.toString());
                jdbc.update("insert into external_identity_binding_occurrences values (?,'step4c',?,?,1,now())",id,id.toString(),id);
                jdbc.update("""
                    insert into external_identity_binding_facts(event_id,issuer,subject,binding_revision,fact_type,
                      user_id,binding_id,recorded_at,partition_hash) values (?,'step4c',?,1,'ATTACHED',?,?,now(),0)
                    """,id,id.toString(),id,id);
            }
            case USER_CREATED_FACT -> {
                userParents(jdbc,id);
                jdbc.update("insert into user_created_facts values (?,?,?,now())",id,id,id);
            }
        }
    }
    static void userParents(JdbcTemplate jdbc, UUID id) {
        jdbc.update("insert into users values (?)",id);
        jdbc.update("insert into registration_requests(request_id,issuer,subject,created_at) values (?,'step4c',?,now())",id,id.toString());
    }
    static void command(JdbcTemplate jdbc, UUID id) {
        jdbc.update("""
            insert into recorded_commands(command_id,command_type,payload_json,submitted_at,auth_issuer,
              auth_valid_until,auth_subject,binding_id,auth_external_authorities_json)
            values (?,'TEST','{}',now(),'step4c',now()+interval '1 hour','subject',?,'[]')
            """,id,id);
    }
}
