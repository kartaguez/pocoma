package com.kartaguez.pocoma.runtime.task.v3;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.runtime.task.v3.SubobjectPostgresFixture.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.*;

@Testcontainers
class SubobjectMigrationPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer(IMAGE).withDatabaseName("i1_read");
    @BeforeAll static void init() { initialize(PG); }

    String normalized(SubobjectPostgresFixture f,Object value) {
        return value.toString().replace(f.adminRole,"ADMIN").replace(f.runtimeRole,"RUNTIME").replace(f.reactivationRole,"REACTIVATION").replace(f.schema,"SCHEMA");
    }
    Map<String,String> catalog(SubobjectPostgresFixture f,boolean business) {
        var result=new TreeMap<String,String>();
        String tables=business ? "('read_subobject_version')" : "('consumption_routing_contract','partition_ownership','discovery_checkpoint','consumption_slot')";
        result.put("columns",normalized(f,f.oracle.queryForList("select table_name,column_name,ordinal_position,data_type,is_nullable,column_default from information_schema.columns where table_schema=current_schema() and table_name in "+tables+" order by table_name,ordinal_position")));
        result.put("constraints",normalized(f,f.oracle.queryForList("select c.relname,t.conname,pg_get_constraintdef(t.oid) as definition,t.condeferrable,t.condeferred from pg_constraint t join pg_class c on c.oid=t.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=current_schema() and c.relname in "+tables+" order by c.relname,t.conname")));
        result.put("indexes",normalized(f,f.oracle.queryForList("select tablename,indexname,indexdef from pg_indexes where schemaname=current_schema() and tablename in "+tables+" order by tablename,indexname")));
        result.put("triggers",normalized(f,f.oracle.queryForList("select c.relname,t.tgname,t.tgenabled,pg_get_triggerdef(t.oid) as definition from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=current_schema() and c.relname in "+tables+" and not t.tgisinternal order by c.relname,t.tgname")));
        result.put("acl",normalized(f,f.oracle.queryForList("select c.relname,c.relacl::text,pg_get_userbyid(c.relowner) as owner from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname=current_schema() and c.relname in "+tables+" order by c.relname")));
        result.put("columnAcl",normalized(f,f.oracle.queryForList("select c.relname,a.attname,a.attacl::text from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=current_schema() and c.relname in "+tables+" and a.attnum>0 order by c.relname,a.attnum")));
        String functions=business ? "('read_subobject_immutable')" : "('consumption_hash_v1','consumption_row_guard','consumption_contract_immutable')";
        result.put("functions",normalized(f,f.oracle.queryForList("select p.proname,p.prosecdef,p.proconfig::text,p.proacl::text,pg_get_functiondef(p.oid) as definition from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname=current_schema() and p.proname in "+functions+" order by p.proname")));
        f.trace.add((business ? "business" : "L2")+" catalog="+result);return result;
    }
    Map<String,Object> l2Rows(SubobjectPostgresFixture f) {
        var result=new TreeMap<String,Object>();
        for(String table:List.of("consumption_routing_contract","partition_ownership","discovery_checkpoint","consumption_slot"))
            result.put(table,f.oracle.queryForList("select * from "+table+" order by 1,2"));
        f.trace.add("L2 rows="+result);return result;
    }
    @Test void freshAndExtension() throws Exception {
        try(var fresh=new SubobjectPostgresFixture(PG,"freshMigration");var extension=new SubobjectPostgresFixture(PG,"populatedL2Extension",false)) {
            var values=eight(UUID.randomUUID());var id=extension.seed(values);
            var engine=extension.engine(extension.participant());var handle=extension.acquire(engine,id);
            var before=l2Rows(extension);var definitions=catalog(extension,false);
            var v1=extension.oracle.queryForMap("select * from flyway_schema_history where version='1'");
            assertEquals(1,extension.migrate(true)); assertEquals(0,extension.migrate(true));
            assertEquals(before,l2Rows(extension));assertEquals(definitions,catalog(extension,false));
            assertEquals(v1,extension.oracle.queryForMap("select * from flyway_schema_history where version='1'"));
            assertEquals(List.of("1","2"),extension.oracle.queryForList("select version from flyway_schema_history where version is not null order by installed_rank",String.class));
            assertEquals(catalog(fresh,true),catalog(extension,true));
            assertEquals(com.kartaguez.pocoma.engine.consumption.v3.ConsumptionEngine.Status.COMMITTED,engine.execute(handle).status());
            assertEquals(0,extension.slotCount(id));assertEquals(8,extension.read(values).size());
            // Reconnected runtime sessions retain the untouched L2 checkpoints and business rows.
            var reconnect=new JdbcTemplate(extension.datasource(extension.runtimeRole));
            assertEquals(17L,reconnect.queryForObject("select position from discovery_checkpoint where consumption_id=?",Long.class,extension.definition.id().value()));
            assertEquals(8,reconnect.queryForObject("select count(*) from read_subobject_version",Integer.class));
        }
    }
    @Test void physicalImmutability() throws Exception {
        try(var f=new SubobjectPostgresFixture(PG,"physicalImmutability")) {
            f.insert(eight(UUID.randomUUID()));var before=f.oracle.queryForList("select * from read_subobject_version order by kind");
            for(String command:List.of("update read_subobject_version set payload=payload","update read_subobject_version set payload='{\"deleted\":true}' where kind='POT_HEADER'", "delete from read_subobject_version", "truncate read_subobject_version")) {
                var runtime=assertThrows(RuntimeException.class,()->f.sql.execute(command));assertEquals("42501",state(runtime));
                var admin=assertThrows(RuntimeException.class,()->f.oracle.execute(command));assertEquals("23514",state(admin));
                assertEquals(before,f.oracle.queryForList("select * from read_subobject_version order by kind"));
                f.trace.add("refused="+command+" runtime="+state(runtime)+" admin="+state(admin));
            }
            assertFalse(f.oracle.queryForObject("select has_schema_privilege(?,current_schema(),'CREATE')",Boolean.class,f.runtimeRole));
            assertFalse(f.oracle.queryForObject("select has_table_privilege(?,'read_subobject_version','UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES') or has_any_column_privilege(?,'read_subobject_version','UPDATE,REFERENCES')",Boolean.class,f.runtimeRole,f.runtimeRole));
            assertTrue(f.oracle.queryForObject("select has_table_privilege(?,'consumption_slot','DELETE')",Boolean.class,f.runtimeRole));
            assertEquals(0,f.oracle.queryForObject("select count(*) from pg_roles where rolname<>? and pg_has_role(?,oid,'MEMBER')",Integer.class,f.runtimeRole,f.runtimeRole));
            assertFalse(f.oracle.queryForObject("select has_function_privilege(?,'read_subobject_immutable()','EXECUTE')",Boolean.class,f.runtimeRole));
            assertEquals(0,f.oracle.queryForObject("select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname=current_schema() and p.prosecdef",Integer.class));
            String reader=f.schema+"_reader";
            var root=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
            root.execute("create role "+reader+" login password 'i1-fixture'");
            f.oracle.execute("grant usage on schema "+f.schema+" to "+reader+";grant select on read_subobject_version to "+reader);
            var read=new JdbcTemplate(f.datasource(reader));assertEquals(8,read.queryForObject("select count(*) from read_subobject_version",Integer.class));
            assertEquals("42501",state(assertThrows(RuntimeException.class,()->read.execute("insert into read_subobject_version select * from read_subobject_version"))));
            assertEquals(2,f.oracle.queryForObject("select count(*) from pg_trigger where tgrelid='read_subobject_version'::regclass and not tgisinternal and tgenabled='A'",Integer.class));
            assertEquals(1,f.oracle.queryForObject("select count(*) from pg_indexes where schemaname=current_schema() and tablename='read_subobject_version'",Integer.class));
            assertEquals("PRIMARY KEY (pot_id, kind, object_id, local_version)",f.oracle.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid='read_subobject_version'::regclass and contype='p'",String.class));
            assertEquals(0,f.oracle.queryForObject("select count(*) from pg_constraint where conrelid='read_subobject_version'::regclass and contype='f'",Integer.class));
            assertEquals("search_path=pg_catalog, pg_temp",f.oracle.queryForObject("select proconfig[1] from pg_proc where oid='read_subobject_immutable()'::regprocedure",String.class));
            catalog(f,true);
        }
    }
    @Test void keyAndKindAndSqlShapes() throws Exception {
        try(var f=new SubobjectPostgresFixture(PG,"sqlShapeConstraints")) {
            var p=UUID.randomUUID();
            String insert="insert into read_subobject_version values (?,?,?,?,?,?::jsonb)";
            var invalid=List.<Object[]>of(new Object[]{p,"POT_HEADER",p,0,1,"{\"deleted\":false}"},new Object[]{p,"POT_HEADER",UUID.randomUUID(),1,1,"{\"deleted\":false}"},
                    new Object[]{p,"UNKNOWN",p,1,1,"{\"deleted\":false}"},new Object[]{p,"POT_HEADER",p,1,2,"{\"deleted\":false}"},
                    new Object[]{p,"POT_HEADER",p,1,1,"{}"},new Object[]{p,"POT_HEADER",p,1,1,"{\"deleted\":null}"},
                    new Object[]{p,"POT_HEADER",p,1,1,"{\"deleted\":false,\"extra\":0}"},new Object[]{p,"POT_HEADER",p,1,1,"{\"deleted\":\"false\"}"},
                    new Object[]{p,"SHAREHOLDER_DETAILS",p,1,1,"{\"name\":\"A\"}"},new Object[]{p,"SHAREHOLDER_DETAILS",p,1,1,"{\"name\":null,\"userId\":null}"},
                    new Object[]{p,"POT_DETAILS",p,1,1,"{\"label\":\"A\",\"creatorId\":null}"},new Object[]{p,"POT_DETAILS",p,1,1,"{\"label\":1,\"creatorId\":\"A\"}"},
                    new Object[]{p,"SHAREHOLDER_WEIGHT",p,1,1,"{\"defaultWeight\":1}"},new Object[]{p,"EXPENSE_DETAILS",p,1,1,"{\"label\":\"E\",\"date\":null}"},
                    new Object[]{p,"EXPENSE_FINANCIALS",p,1,1,"{\"amount\":{},\"payerId\":\"x\",\"participants\":[]}"},
                    new Object[]{p,"EXPENSE_FINANCIALS",p,1,1,"{\"amount\":{},\"payerId\":\"x\",\"participants\":{}}"},
                    new Object[]{null,"POT_HEADER",p,1,1,"{\"deleted\":false}"},new Object[]{p,"POT_HEADER",p,1,1,null});
            for(var row:invalid) {
                String state=state(assertThrows(RuntimeException.class,()->f.oracle.update(insert,row)));
                assertTrue(Set.of("23514","23502").contains(state),state);f.trace.add("SQL shape rejected="+Arrays.toString(row)+" state="+state);
            }
            assertEquals(0,f.count());
        }
    }
    @Test void wrongTargetAndUnsafeRoles() throws Exception {
        for(String parameter:List.of("expectedDatabase","expectedSchema","expectedAdmin","expectedRuntime")) {
            try(var f=new SubobjectPostgresFixture(PG,"wrongTarget-"+parameter,false)) {
                var before=l2Rows(f);
                assertThrows(RuntimeException.class,()->f.flyway(true,Map.of(parameter,"invalid_i1_target")).migrate());
                assertEquals(before,l2Rows(f));assertNull(f.oracle.queryForObject("select to_regclass('read_subobject_version')::text",String.class));
            }
        }
        try(var f=new SubobjectPostgresFixture(PG,"unsafeMembership",false)) {
            var root=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
            root.execute("grant "+f.adminRole+" to "+f.runtimeRole);
            assertThrows(RuntimeException.class,()->f.migrate(true));
            assertNull(f.oracle.queryForObject("select to_regclass('read_subobject_version')::text",String.class));
        }
    }
    @Test void auxiliaryDefinerCannotExposeMutation() throws Exception {
        for(boolean outside:List.of(false,true)) {
            try(var f=new SubobjectPostgresFixture(PG,"unsafeAuxiliaryDefiner-"+outside,false)) {
                String function="fixture_unsafe_auxiliary";
                if(outside) {
                    String auxiliary=f.schema+"_aux";
                    new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()))
                            .execute("create schema "+auxiliary+" authorization "+f.adminRole);
                    f.oracle.execute("grant usage on schema "+auxiliary+" to "+f.runtimeRole);
                    function=auxiliary+"."+function;
                }
                f.oracle.execute("create function "+function+"() returns void language plpgsql security definer set search_path=pg_catalog,pg_temp as $$ begin "
                        +"execute 'alter table "+f.schema+".read_subobject_version disable trigger user';"
                        +"execute 'delete from "+f.schema+".read_subobject_version';end $$");
                assertTrue(f.oracle.queryForObject("select has_function_privilege(?,?,'EXECUTE')",Boolean.class,f.runtimeRole,function+"()"));
                assertThrows(RuntimeException.class,()->f.migrate(true));
                assertNull(f.oracle.queryForObject("select to_regclass('read_subobject_version')::text",String.class));
                assertEquals(List.of("1"),f.oracle.queryForList("select version from flyway_schema_history where version is not null",String.class));
                f.trace.add("unsafe accessible definer refused="+function);
            }
        }
    }
    @Test void populatedV1CannotBeReplayedWithoutHistory() throws Exception {
        try(var f=new SubobjectPostgresFixture(PG,"noV1Replay",false)) {
            var id=f.seed(eight(UUID.randomUUID()));var before=l2Rows(f);
            f.oracle.execute("alter table flyway_schema_history rename to fixture_saved_history");
            assertThrows(RuntimeException.class,()->f.migrate(true));
            String v1=Files.readString(ROOT.resolve("app/infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql"));
            for(var e:Map.of("expectedDatabase",PG.getDatabaseName(),"expectedSchema",f.schema,"expectedAdmin",f.adminRole,"expectedRuntime",f.runtimeRole).entrySet()) v1=v1.replace("${"+e.getKey()+"}",e.getValue());
            String replay=v1;
            assertEquals("23514",state(assertThrows(RuntimeException.class,()->f.oracle.execute(replay))));
            assertEquals(before,l2Rows(f));assertEquals(1,f.slotCount(id));
        }
    }
    static String state(Throwable failure) {
        for(Throwable t=failure;t!=null;t=t.getCause()) if(t instanceof SQLException sql) return sql.getSQLState();
        return "NO_SQLSTATE";
    }
}
