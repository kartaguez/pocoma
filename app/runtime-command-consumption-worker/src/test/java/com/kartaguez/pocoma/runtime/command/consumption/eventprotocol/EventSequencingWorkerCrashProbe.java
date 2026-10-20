package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.consume.command.pot.publication.AppendBusinessEventService;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.*;

/** Separate JVM killed by the parent after actual E/associations, before physical commit. */
public final class EventSequencingWorkerCrashProbe {
    public static void main(String[] args) throws Exception {
        try(var context=new SpringApplicationBuilder(PocomaCommandConsumptionWorkerApplication.class).web(WebApplicationType.NONE)
            .run("--spring.datasource.url="+System.getenv("STEP4E_URL"),"--spring.datasource.username="+System.getenv("STEP4E_USER"),
                "--spring.datasource.password="+System.getenv("STEP4E_PASSWORD"),"--pocoma.command-consumption.enabled=false")) {
            var manager=context.getBean(JpaTransactionManager.class);var jdbc=context.getBean(JdbcTemplate.class);
            var seq=new JdbcEventTransactionSequencer(manager);
            var owner=new PrimaryEventTransactionRunner(manager,batch->{
                seq.execute(batch);
                try {
                    Files.writeString(Path.of(System.getenv("STEP4E_SIGNAL")),jdbc.queryForObject("select pg_backend_pid()",Integer.class)+","+batch.inFamily(com.kartaguez.pocoma.port.transaction.EventFamily.BUSINESS_EVENT).getFirst().eventId());
                    if(!new CountDownLatch(1).await(60,TimeUnit.SECONDS))throw new IllegalStateException("parent did not kill probe");
                } catch(Exception e){throw new IllegalStateException(e);}
            });
            var writer=new JpaBusinessEventOutboxAdapter(context.getBean(EntityManagerFactory.class),new com.fasterxml.jackson.databind.ObjectMapper());
            new AppendBusinessEventService(writer,owner,owner::recordPublishedEvent).append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));
        }
    }
}
