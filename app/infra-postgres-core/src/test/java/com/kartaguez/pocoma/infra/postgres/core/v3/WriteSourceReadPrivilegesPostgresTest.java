package com.kartaguez.pocoma.infra.postgres.core.v3;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
/** Isolated effective-grant controls, including NOINHERIT membership and column grants. */
@Testcontainers
class WriteSourceReadPrivilegesPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73").asCompatibleSubstituteFor("postgres"));
    private String role,schema;
    private JdbcTemplate admin;
    private V3BootstrapGuard.Target target;
    @BeforeEach void fixture() {
        String suffix=UUID.randomUUID().toString().replace("-","");schema="s_"+suffix;role="r_"+suffix;
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        admin.execute("revoke create,temporary on database "+PG.getDatabaseName()+" from public");
        admin.execute("revoke create on schema public from public");
        admin.execute("create schema "+schema);
        admin.execute("create role "+role+" login password 'isolated-source' noinherit");
        admin.execute("grant usage on schema "+schema+" to "+role);
        admin.execute("create table "+schema+".v3_pot_identity(id uuid primary key,version bigint)");
        admin.execute("grant select on "+schema+".v3_pot_identity to "+role);
        String url=PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema;
        target=new V3BootstrapGuard.Target(new DriverManagerDataSource(url,role,"isolated-source"),PG.getDatabaseName(),schema,role);
    }
    @AfterEach void cleanup() {
        admin.execute("drop schema "+schema+" cascade");
        admin.execute("drop owned by "+role);admin.execute("drop role "+role);
    }
    @Test void selectIsAcceptedAndActualMutationIsDenied() throws Exception {
        assertDoesNotThrow(() -> WriteSourceReadPrivileges.validate(target));
        try(var c=target.source().getConnection();var s=c.createStatement()) {
            assertDoesNotThrow(() -> s.executeQuery("select * from v3_pot_identity").close());
            var denied=assertThrows(java.sql.SQLException.class,() -> s.executeUpdate("insert into v3_pot_identity values(gen_random_uuid(),1)"));
            assertEquals("42501",denied.getSQLState());
        }
    }
    @Test void columnMutationCannotHideBehindReadOnlyFlag() {
        admin.execute("grant update(version) on "+schema+".v3_pot_identity to "+role);reject();
    }
    @Test void noinheritMembershipStillExposesSetRoleAuthority() {
        String group=role+"g";
        admin.execute("create role "+group);admin.execute("grant update on "+schema+".v3_pot_identity to "+group);
        admin.execute("grant "+group+" to "+role);
        try {reject();} finally {admin.execute("drop owned by "+group);admin.execute("drop role "+group);}
    }
    @Test void ownershipAndDdlAreRejected() {admin.execute("grant create on schema "+schema+" to "+role);reject();}
    @Test void securityDefinerExecuteIsRejected() {
        admin.execute("create function "+schema+".privileged() returns integer language sql security definer as 'select 1'");reject();
    }
    @Test void sequenceAndIndirectViewAccessAreRejected() {
        admin.execute("create sequence "+schema+".source_sequence");admin.execute("grant usage on sequence "+schema+".source_sequence to "+role);reject();
        admin.execute("revoke all on sequence "+schema+".source_sequence from "+role);
        admin.execute("create view "+schema+".hidden_source as select * from "+schema+".v3_pot_identity");admin.execute("grant select on "+schema+".hidden_source to "+role);reject();
    }
    @Test void principalIdentityIsPartOfTheGuard() {
        var wrong=new V3BootstrapGuard.Target(target.source(),target.database(),target.schema(),role+"wrong");
        assertThrows(IllegalStateException.class,() -> WriteSourceReadPrivileges.validate(wrong));
    }
    private void reject(){assertThrows(IllegalStateException.class,() -> WriteSourceReadPrivileges.validate(target));}
}
