package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.*;
public final class LateCallbackPoisonProbe {
    public static void main(String[] args) throws Exception {
        CommandExecutionPostgresTest.POSTGRES.start();
        var f=new CommandExecutionPostgresTest();f.setup();var factory=f.entityManagerFactory(f.source);
        try {
            var manager=new JpaTransactionManager(factory.getObject());manager.setDataSource(f.source);manager.setNestedTransactionAllowed(false);
            var owner=new SpringAttemptTransactions(f.source,manager);
            Throwable observed=null;
            try {owner.write(() -> {
                SpringAttemptTransactions.guarded(f.source,() -> {f.jdbc.update("insert into v3_command_probe values(991,'prefix')");return null;});
                owner.flushBeforePublication();SpringAttemptTransactions.closePublication(f.source);owner.requirePublicationClosed();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    public void beforeCommit(boolean readOnly) {
                        try {SpringAttemptTransactions.guarded(f.source,() -> {f.jdbc.update("insert into v3_command_probe values(992,'late')");return null;});}
                        catch(RuntimeException caught){System.out.println("caught beforeCommit guarded writer="+caught);}
                    }
                });return null;
            });}catch(RuntimeException failure){observed=failure;}
            var rows=f.jdbc.queryForList("select * from v3_command_probe order by id");
            System.out.println("LATE CALLBACK PHYSICAL ORACLE failure="+observed+" durable="+rows);
            if(observed==null || !rows.isEmpty())throw new AssertionError("caught guarded beforeCommit violation did not poison JPA commit");
        }finally{factory.destroy();f.threads.shutdownNow();CommandExecutionPostgresTest.POSTGRES.stop();}
    }
}
