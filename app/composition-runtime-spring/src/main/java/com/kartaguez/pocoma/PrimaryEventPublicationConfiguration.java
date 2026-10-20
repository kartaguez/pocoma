package com.kartaguez.pocoma;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.kartaguez.pocoma.runtime.publication.PrimaryEventProtocol;
import com.kartaguez.pocoma.port.transaction.PublishedEvents;
import com.kartaguez.pocoma.engine.consume.command.pot.publication.AppendBusinessEventService;
import com.kartaguez.pocoma.engine.write.pot.port.event.BusinessEventAppendPort;

/** Imported explicitly by producer compositions; does not replace unrelated runtime runners. */
public class PrimaryEventPublicationConfiguration {
    @Bean
    PrimaryEventProtocol primaryEventProtocol(PlatformTransactionManager manager, EntityManagerFactory emf,
            JdbcTemplate jdbc) {
        return new PrimaryEventProtocol(manager, emf, jdbc);
    }

    @Bean
    PublishedEvents publishedEvents(PrimaryEventProtocol protocol) {
        return protocol.runner()::recordPublishedEvent;
    }

    @Bean
    AppendBusinessEventService appendBusinessEventService(BusinessEventAppendPort writer,
            PrimaryEventProtocol protocol, PublishedEvents publications) {
        return new AppendBusinessEventService(writer, protocol.runner(), publications);
    }
}
