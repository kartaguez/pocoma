package com.kartaguez.pocoma.runtime.command.consumption.sequencing;

import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;

/** Test-only owner. No component scanning, production schema, Flyway or runtime override. */
final class TerminalSequencingFixture {
    // Explicit protocol order, independent of publication order and SQL collation.
    enum Family { BUSINESS_EVENT, COMMAND_TERMINAL, BINDING_FACT, USER_CREATED }
    record Event(Family family, UUID id) {}
    static final class Collection {
        boolean closed;
        final List<Event> events = new ArrayList<>();
        final List<Runnable> deferred = new ArrayList<>();
        final List<String> trace = new ArrayList<>();
        Runnable afterFirstE = () -> {};
        Runnable afterSuffix = () -> {};
        boolean invalidAssociation;
        long waitNanos, acquiredNanos, endNanos;
        int pid;
        long xid;
        void open() { if (closed) throw new IllegalStateException("collection closed"); }
    }
    final JdbcTemplate sql;
    final Owner owner;
    final SpringTransactionRunner runner;
    final AnnotatedBoundary annotated;

    TerminalSequencingFixture(DataSource ds, boolean sequence) {
        sql = new JdbcTemplate(ds);
        owner = new Owner(ds, sequence);
        runner = new SpringTransactionRunner(new TransactionTemplate(owner));
        ProxyFactory proxy = new ProxyFactory(new AnnotatedBoundary());
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(owner, new AnnotationTransactionAttributeSource()));
        annotated = (AnnotatedBoundary) proxy.getProxy();
    }
    public static class AnnotatedBoundary {
        @Transactional public <T> T execute(Supplier<T> action) { return action.get(); }
    }
    void schema() {
        sql.execute("create schema experiment");
        sql.execute("""
            create table experiment.authority (
                family text primary key check (family in
                  ('BUSINESS_EVENT','COMMAND_TERMINAL','BINDING_FACT','USER_CREATED')),
                last_position bigint not null check (last_position >= 0))
            """);
        for (Family f : Family.values()) sql.update("insert into experiment.authority values (?,0)", f.name());
        sql.execute("""
            create table experiment.event (
                family text not null check (family in
                  ('BUSINESS_EVENT','COMMAND_TERMINAL','BINDING_FACT','USER_CREATED')),
                event_id uuid not null, payload text not null,
                primary key(family,event_id))
            """);
        sql.execute("""
            create table experiment.event_position (
                family text not null, position bigint not null check(position > 0), event_id uuid not null,
                primary key(family,position), unique(family,event_id),
                foreign key(family,event_id) references experiment.event(family,event_id))
            """);
        for (String table : List.of("p", "b", "s")) {
            sql.execute("create table experiment." + table + " (id integer primary key, version integer not null)");
            sql.update("insert into experiment." + table + " values (1,0),(2,0),(3,0)");
        }
    }
    void reset() {
        sql.execute("truncate experiment.event_position, experiment.event");
        sql.update("update experiment.authority set last_position=0");
        for (String t : List.of("p", "b", "s")) sql.update("update experiment." + t + " set version=0");
    }
    Collection current() {
        Object value = TransactionSynchronizationManager.getResource(owner.key);
        if (!(value instanceof Collection c)) throw new IllegalStateException("no physical owner");
        return c;
    }
    UUID publish(Family family, UUID id, String payload) {
        Collection c = current(); c.open();
        sql.update("insert into experiment.event values (?,?,?)", family.name(), id, payload);
        c.events.add(new Event(family, id)); // Only after the actual successful INSERT.
        c.trace.add("EVENT:" + family);
        return id;
    }
    UUID publish(Family family) { return publish(family, UUID.randomUUID(), "fixture"); }
    void business(String table, int id) {
        Collection c = current(); c.open();
        if (!List.of("p", "b", "s").contains(table)) throw new IllegalArgumentException(table);
        if (table.equals("b")) sql.queryForObject("select version from experiment.b where id=? for share", Integer.class, id);
        else sql.update("update experiment." + table + " set version=version+1 where id=?", id);
        c.trace.add(table.toUpperCase(Locale.ROOT));
    }
    long counter(Family family) {
        return sql.queryForObject("select last_position from experiment.authority where family=?", Long.class, family.name());
    }
    long count(String table) {
        if (!List.of("event", "event_position").contains(table)) throw new IllegalArgumentException(table);
        return sql.queryForObject("select count(*) from experiment." + table, Long.class);
    }
    final class Owner extends DataSourceTransactionManager {
        final Object key = new Object();
        final boolean sequence;
        Owner(DataSource ds, boolean sequence) { super(ds); this.sequence = sequence; setNestedTransactionAllowed(false); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            super.doBegin(transaction, definition);
            Collection c = new Collection();
            TransactionSynchronizationManager.bindResource(key, c);
            c.pid = sql.queryForObject("select pg_backend_pid()", Integer.class);
            c.xid = sql.queryForObject("select txid_current()", Long.class);
        }
        @Override protected Object doSuspend(Object transaction) {
            throw new IllegalStateException("experimental contract forbids suspension / REQUIRES_NEW");
        }
        @Override protected void prepareForCommit(DefaultTransactionStatus status) {
            if (!status.isNewTransaction()) return; // REQUIRED participant return is not a physical commit.
            Collection c = current();
            c.trace.add("FINALIZATIONS_DONE");
            c.deferred.forEach(Runnable::run); // Concrete deferred SQL flush, before any E.
            c.trace.add("FLUSH");
            c.closed = true;
            if (!sequence || c.events.isEmpty()) { c.afterSuffix.run(); return; }
            for (Family family : Family.values()) {
                List<UUID> ids = c.events.stream().filter(e -> e.family() == family).map(Event::id)
                    .sorted(Comparator.comparing(UUID::toString)).toList();
                if (ids.isEmpty()) continue;
                long start = System.nanoTime();
                long end = sql.queryForObject("""
                    update experiment.authority set last_position=last_position+?
                    where family=? returning last_position
                    """, Long.class, ids.size(), family.name());
                c.waitNanos += System.nanoTime() - start;
                c.trace.add("E:" + family);
                if (c.acquiredNanos == 0) { c.acquiredNanos = System.nanoTime(); c.afterFirstE.run(); }
                for (int i = 0; i < ids.size(); i++) {
                    sql.update("insert into experiment.event_position values (?,?,?)", family.name(),
                        end - ids.size() + i + 1, c.invalidAssociation && family == Family.COMMAND_TERMINAL ? UUID.randomUUID() : ids.get(i));
                    c.trace.add("POSITION:" + family);
                }
            }
            c.afterSuffix.run();
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            Collection c = current();
            // Same physical backend and transaction all the way to the suffix and COMMIT.
            if (c.pid != sql.queryForObject("select pg_backend_pid()", Integer.class)
                    || c.xid != sql.queryForObject("select txid_current()", Long.class))
                throw new IllegalStateException("physical transaction changed");
            super.doCommit(status);
            c.endNanos = System.nanoTime(); c.trace.add("COMMIT");
        }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            Collection c = (Collection) TransactionSynchronizationManager.unbindResource(key);
            c.events.clear(); c.deferred.clear(); c.closed = true;
            try { super.doCleanupAfterCompletion(transaction); }
            finally { c.afterFirstE = () -> {}; c.afterSuffix = () -> {}; }
        }
    }
}
