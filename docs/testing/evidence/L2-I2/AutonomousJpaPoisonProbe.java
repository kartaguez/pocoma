package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
public final class AutonomousJpaPoisonProbe {
    public static void main(String[] args) throws Exception {
        CommandExecutionPostgresTest.POSTGRES.start();
        var f=new CommandExecutionPostgresTest();f.setup();var factory=f.entityManagerFactory(f.source);
        try {
            var manager=new JpaTransactionManager(factory.getObject());manager.setDataSource(f.source);manager.setNestedTransactionAllowed(false);
            Throwable observed=null;
            try {new TransactionTemplate(manager).execute(status -> {
                f.jdbc.update("insert into v3_command_probe values(993,'autonomous prefix')");
                try {SpringAttemptTransactions.revision(f.source,() -> {throw new IllegalArgumentException("caught B validation");});}
                catch(RuntimeException caught){System.out.println("caught autonomous B guarded failure="+caught);}
                return null;
            });}catch(RuntimeException failure){observed=failure;}
            var rows=f.jdbc.queryForList("select * from v3_command_probe order by id");
            System.out.println("AUTONOMOUS JPA PHYSICAL ORACLE failure="+observed+" durable="+rows);
            if(observed==null || !rows.isEmpty())throw new AssertionError("autonomous B caught failure did not poison JPA caller");
        }finally{factory.destroy();f.threads.shutdownNow();CommandExecutionPostgresTest.POSTGRES.stop();}
    }
}
