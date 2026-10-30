import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.*;
import javax.sql.DataSource;
import com.zaxxer.hikari.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.*;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestStore;
import com.kartaguez.pocoma.engine.consumption.v3.RecoveryObservation;

public class IndependentAudit {
 static final String URL="jdbc:postgresql://127.0.0.1:55439/audit_write?currentSchema=audit&connectTimeout=3&socketTimeout=8";
 static final ExternalIdentity KEY=new ExternalIdentity("audit","anchor");
 static final ExternalUserIdentityId ID=new ExternalUserIdentityId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
 static final JdbcTemplate ADMIN=new JdbcTemplate(new DriverManagerDataSource(URL,"postgres",""));
 static void check(boolean v,String message){if(!v) throw new AssertionError(message);}
 static void emit(String name,Object value){System.out.println("AUDIT "+name+" "+value);}
 static HikariDataSource pool(int max){var p=new HikariDataSource();p.setJdbcUrl(URL);p.setUsername("audit_w");p.setPassword("");p.setMaximumPoolSize(max);p.setMinimumIdle(0);p.setConnectionTimeout(3000);return p;}
 static SpringAttemptTransactions owner(DataSource s){var m=new DataSourceTransactionManager(s);m.setNestedTransactionAllowed(false);return new SpringAttemptTransactions(s,m,10);}
 static Object[] witness(DataSource s){var j=new JdbcTemplate(s);return new Object[]{TransactionSynchronizationManager.getResource(s),j.queryForObject("select pg_backend_pid()",Integer.class),j.queryForObject("select pg_current_xact_id()::text",String.class)};}
 static String physicalProbe(){try(var c=DriverManager.getConnection(URL,"audit_w", "")){c.setAutoCommit(false);try(var st=c.createStatement()){st.executeQuery("select eui_id from v3_eui_authority where issuer='audit' and subject='anchor' for update nowait");return "ACQUIRED";}catch(SQLException e){return e.getSQLState();}finally{c.rollback();}}catch(SQLException e){throw new RuntimeException(e);}}
 static void denied(SpringAttemptTransactions tx,Runnable call,String label){try{tx.write(()->{call.run();return null;});throw new AssertionError(label+" allowed");}catch(AttemptTransactions.Failure f){check(f.resolution()==AttemptTransactions.Resolution.ROLLED_BACK,label+" resolution");emit(label,"DENIED ROLLED_BACK");}}
 static void record(String label,Object[] w){emit(label,"holder="+System.identityHashCode(w[0])+" pid="+w[1]+" xid="+w[2]);}
 static IdentityTransitionResult.Changed fakeChange(){return new IdentityTransitionResult.Changed(Optional.empty(),new ExternalUserIdentity(ID,KEY,1,new PocomaUserId(UUID.randomUUID()),new BindingToken("audit-test-only")));}
 static void normal() throws Exception {
  try(var p=pool(1)){
   var tx=owner(p);var a=new JdbcIdentityAuthority(p,tx);Object[][] seen=new Object[2][];
   denied(tx,()->a.current(KEY),"A.current.without.lock");
   denied(tx,()->a.apply(fakeChange(),UUID.randomUUID()),"A.apply.without.lock");
   for(int n=0;n<2;n++){final int i=n;tx.write(()->{a.lockInCanonicalOrder(List.of(KEY));seen[i]=witness(p);check(physicalProbe().equals("55P03"),"physical lock missing");a.current(KEY);return null;});record("A.normal."+n,seen[n]);check(physicalProbe().equals("ACQUIRED"),"lock retained after commit");}
   check(seen[0][0]!=seen[1][0],"normal holder reused");check(seen[0][1].equals(seen[1][1]),"Hikari physical connection not reused");check(!seen[0][2].equals(seen[1][2]),"same xid");
   denied(tx,()->a.current(KEY),"A.current.after.commit");
   try{tx.write(()->{a.lockInCanonicalOrder(List.of(KEY));check(physicalProbe().equals("55P03"),"rollback path unprotected");throw new IllegalStateException("audit rollback");});}catch(AttemptTransactions.Failure f){check(f.resolution()==AttemptTransactions.Resolution.ROLLED_BACK,"rollback unconfirmed");}
   check(physicalProbe().equals("ACQUIRED"),"rollback left lock");denied(tx,()->a.current(KEY),"A.current.after.rollback");
   tx.write(()->{a.lockInCanonicalOrder(List.of(KEY));check(physicalProbe().equals("55P03"),"retry unprotected");a.current(KEY);return null;});
   // Retain completed holders while measuring; values must not point back to keys.
   var field=JdbcIdentityAuthority.class.getDeclaredField("protections");field.setAccessible(true);var map=(Map<?,?>)field.get(a);emit("A.retention.with.holders",map.size());
   nullOut(seen);
  }
 }
 static Object[][] nullOut(Object[][] x){Arrays.fill(x,null);return x;}
 static void retainedHolder() throws Exception {
  try(var p=pool(1);var c=p.getConnection()){
   var holder=new ConnectionHolder(c);TransactionSynchronizationManager.bindResource(p,holder);
   try{
    var tx=owner(p);var a=new JdbcIdentityAuthority(p,tx);Object[][] seen=new Object[2][];
    tx.write(()->{a.lockInCanonicalOrder(List.of(KEY));seen[0]=witness(p);check(physicalProbe().equals("55P03"),"T1 no lock");return null;});
    check(physicalProbe().equals("ACQUIRED"),"T1 still protected");
    tx.write(()->{seen[1]=witness(p);a.current(KEY);check(physicalProbe().equals("ACQUIRED"),"T2 unexpectedly locked");emit("A.retained.current", "ALLOWED_WITHOUT_PHYSICAL_LOCK");return null;});
    record("A.retained.T1",seen[0]);record("A.retained.T2",seen[1]);check(seen[0][0]==seen[1][0],"holder not reused");check(!seen[0][2].equals(seen[1][2]),"not distinct tx");
    denied(tx,()->a.lockInCanonicalOrder(List.of(KEY)),"A.retained.reacquire");
   }finally{TransactionSynchronizationManager.unbindResourceIfPossible(p);}
  }
 }
 static void concurrent() throws Exception {
  try(var p=pool(2);var es=Executors.newFixedThreadPool(2)){
   var tx=owner(p);var a=new JdbcIdentityAuthority(p,tx);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var secondPid=new AtomicInteger();var firstPid=new AtomicInteger();
   var first=es.submit(()->tx.write(()->{a.lockInCanonicalOrder(List.of(KEY));firstPid.set((Integer)witness(p)[1]);locked.countDown();try{check(release.await(8,TimeUnit.SECONDS),"barrier timeout");}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
   check(locked.await(8,TimeUnit.SECONDS),"first barrier");
   var second=es.submit(()->{denied(tx,()->a.current(KEY),"A.thread2.without.lock");return tx.write(()->{secondPid.set((Integer)witness(p)[1]);a.lockInCanonicalOrder(List.of(KEY));check(physicalProbe().equals("55P03"),"second unprotected");a.current(KEY);return null;});});
   boolean edge=false;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
   while(System.nanoTime()<end){if(secondPid.get()>0){var blockers=ADMIN.queryForList("select unnest(pg_blocking_pids(?))",Integer.class,secondPid.get());if(blockers.contains(firstPid.get())){edge=true;break;}}Thread.yield();}
   release.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);check(edge,"no physical blocker edge");emit("A.concurrent","BLOCKING_EDGE "+secondPid.get()+"->"+firstPid.get()+" distinct holders/threads; protected successor");
  }
 }
 static void unknown() throws Exception {
  try(var p=pool(1)){
   var lose=new AtomicBoolean();
   DataSource s=new AbstractDataSource(){public Connection getConnection()throws SQLException{var c=p.getConnection();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(o,m,args)->{try{Object r=m.invoke(c,args);if(m.getName().equals("commit")&&lose.compareAndSet(true,false))throw new SQLException("audit lost commit acknowledgement","08006");return r;}catch(InvocationTargetException e){throw e.getCause();}});}public Connection getConnection(String u,String pw)throws SQLException{return getConnection();}};
   var tx=owner(s);var a=new JdbcIdentityAuthority(s,tx);Object[][] seen=new Object[2][];
   var id=UUID.randomUUID();var req=request(id,"unknown-"+id,new IdentityRequest.Registration(),null);var inj=new DriverManagerDataSource(URL,"audit_i","");var itx=owner(inj);var ins=new JdbcIdentityRequestStore(inj,itx);itx.write(()->ins.insertPending(req));var store=new JdbcIdentityRequestStore(s,tx);
   lose.set(true);try{tx.write(()->{var ids=a.lockInCanonicalOrder(List.of(KEY,req.target()));seen[0]=witness(s);check(physicalProbe().equals("55P03"),"unknown prefix no lock");var change=(IdentityTransitionResult.Changed)IdentityTransitions.registration(req.target(),a.current(req.target()),ids.get(req.target()),new PocomaUserId(UUID.randomUUID()),new SecureBindingTokenGenerator().generate());a.apply(change,req.requestId());store.finishTerminal(req,IdentityRequestStore.Result.from(change));store.publish(req,change);return null;});throw new AssertionError("unknown expected");}catch(AttemptTransactions.Failure f){check(f.resolution()==AttemptTransactions.Resolution.UNKNOWN,"not UNKNOWN");emit("A.commit.ack","UNKNOWN after delegated physical COMMIT of full Identity terminal unit; injected JDBC 08006");}
   check(physicalProbe().equals("ACQUIRED"),"commit unknown left lock");
   var obs=new JdbcIdentityRequestObservation(s);check(tx.read(ctx->obs.observe(req.requestId(),ctx))==RecoveryObservation.COMPLETED,"R9 committed");emit("A.UNKNOWN.R9","COMPLETED after real COMMIT; exactly one User/state/result/completion");
   for(String sql:List.of("select count(*) from v3_pocoma_user where created_request_id=?","select count(*) from v3_eui_version where request_id=?","select count(*) from v3_identity_request_result where request_id=?","select count(*) from v3_identity_completion_event where subject_id=?"))check(ADMIN.queryForObject(sql,Long.class,req.requestId())==1,"unknown effect count");
   denied(tx,()->a.current(KEY),"A.current.after.UNKNOWN.R9");
   tx.write(()->{seen[1]=witness(s);a.lockInCanonicalOrder(List.of(KEY));check(physicalProbe().equals("55P03"),"post unknown no lock");return null;});
   record("A.UNKNOWN.before",seen[0]);record("A.UNKNOWN.after",seen[1]);check(seen[0][0]!=seen[1][0],"unknown holder reused");
  }
 }
 static void reconnect()throws Exception{
  try(var p=pool(1)){
   var tx=owner(p);var a=new JdbcIdentityAuthority(p,tx);var old=new AtomicInteger();
   try{tx.write(()->{a.lockInCanonicalOrder(List.of(KEY));old.set((Integer)witness(p)[1]);check(physicalProbe().equals("55P03"),"pre-disconnect no lock");ADMIN.queryForObject("select pg_terminate_backend(?)",Boolean.class,old.get());a.current(KEY);return null;});throw new AssertionError("dead connection accepted");}catch(AttemptTransactions.Failure f){emit("A.reconnect.resolution",f.resolution());}
   denied(tx,()->a.current(KEY),"A.reconnect.current.without.lock");
   tx.write(()->{a.lockInCanonicalOrder(List.of(KEY));var w=witness(p);check(!w[1].equals(old.get()),"same killed backend");check(physicalProbe().equals("55P03"),"new backend no lock");record("A.reconnect.new",w);return null;});
  }
 }
 static V3BootstrapGuard guard(){var w=new DriverManagerDataSource(URL,"audit_w","");var r=new DriverManagerDataSource(URL.replace("audit_write","audit_read"),"audit_r","");return V3BootstrapGuard.development(new V3BootstrapGuard.Target(w,"audit_write","audit","audit_w"),new V3BootstrapGuard.Target(r,"audit_read","audit","audit_r"),V3ConsumptionCatalogConfiguration.definitions(),false,false);}
 static void preflight(){var g=guard();g.validateLifecyclePrivileges(V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);g.validateIdentitySource(new DriverManagerDataSource(URL,"audit_w",""));emit("B.preflight","PASS");}
 static IdentityRequest request(UUID id,String subject,IdentityRequest.Operation op,BindingToken token){var e=new ExternalIdentity("audit",subject);var target=op instanceof IdentityRequest.Link l?l.target():e;var time=java.time.Instant.parse("2026-10-30T00:00:00Z");return new IdentityRequest(id,e,op,token,time,new IdentityRequest.AdmissionContext("independent-audit","L3/I3",e,target,id.toString(),id.toString(),time,Map.of("purpose","synthetic-test"),IdentityRequest.Origin.CONTROLLED_INTERNAL));}
 static void seed(){
  var inj=new DriverManagerDataSource(URL,"audit_i","");var itx=owner(inj);var is=new JdbcIdentityRequestStore(inj,itx);
  var pending=request(UUID.fromString("00000000-0000-0000-0000-000000000099"),"pending",new IdentityRequest.Registration(),null);itx.write(()->is.insertPending(pending));
  var req=request(UUID.fromString("00000000-0000-0000-0000-000000000098"),"seed",new IdentityRequest.Registration(),null);itx.write(()->is.insertPending(req));
  try(var p=pool(1)){var tx=owner(p);var a=new JdbcIdentityAuthority(p,tx);var s=new JdbcIdentityRequestStore(p,tx);tx.write(()->{var ids=a.lockInCanonicalOrder(List.of(req.target()));var change=(IdentityTransitionResult.Changed)IdentityTransitions.registration(req.target(),a.current(req.target()),ids.get(req.target()),new PocomaUserId(UUID.randomUUID()),new BindingToken("AUDIT_SYNTHETIC_TOKEN_ONLY"));a.apply(change,req.requestId());s.finishTerminal(req,IdentityRequestStore.Result.from(change));s.publish(req,change);return null;});}
  var link=request(UUID.fromString("00000000-0000-0000-0000-000000000097"),"seed",new IdentityRequest.Link(new ExternalIdentity("audit","target")),new BindingToken("AUDIT_SYNTHETIC_TOKEN_ONLY"));itx.write(()->is.insertPending(link));
  emit("B.seed","synthetic registration and PENDING admission persisted; no token output");
 }
 static void collision(){
  var inj=new DriverManagerDataSource(URL,"audit_i","");var itx=owner(inj);var is=new JdbcIdentityRequestStore(inj,itx);var req=request(UUID.randomUUID(),"collision",new IdentityRequest.Registration(),null);itx.write(()->is.insertPending(req));
  try(var p=pool(1)){var tx=owner(p);var a=new JdbcIdentityAuthority(p,tx);try{tx.write(()->{var ids=a.lockInCanonicalOrder(List.of(req.target()));var change=(IdentityTransitionResult.Changed)IdentityTransitions.registration(req.target(),a.current(req.target()),ids.get(req.target()),new PocomaUserId(UUID.randomUUID()),new BindingToken("AUDIT_SYNTHETIC_TOKEN_ONLY"));a.apply(change,req.requestId());return null;});throw new AssertionError("collision accepted");}catch(AttemptTransactions.Failure f){var sw=new java.io.StringWriter();f.printStackTrace(new java.io.PrintWriter(sw));check(!sw.toString().contains("AUDIT_SYNTHETIC_TOKEN_ONLY"),"Java token leak");emit("B.collision","Java exception graph REDACTED; resolution="+f.resolution());}}
 }
 static void memory() throws Exception {try(var p=pool(1)){var tx=owner(p);var a=new JdbcIdentityAuthority(p,tx);var refs=new ArrayList<java.lang.ref.WeakReference<Object>>();for(int i=0;i<300;i++)tx.write(()->{a.lockInCanonicalOrder(List.of(KEY));refs.add(new java.lang.ref.WeakReference<>(TransactionSynchronizationManager.getResource(p)));return null;});var f=JdbcIdentityAuthority.class.getDeclaredField("protections");f.setAccessible(true);var map=(Map<?,?>)f.get(a);emit("A.memory.before",map.size());for(int i=0;i<5&&refs.stream().anyMatch(r->r.get()!=null);i++){System.gc();Thread.sleep(50);}long alive=refs.stream().filter(r->r.get()!=null).count();emit("A.memory.after","live weak holders="+alive+" entries="+map.size());check(alive==0&&map.isEmpty(),"holders retained");}}
 static String as(String role,String sql){try(var c=DriverManager.getConnection(URL,role, "")){c.setAutoCommit(false);try(var st=c.createStatement()){if(sql.startsWith("set role ")){int sep=sql.indexOf(';');st.execute(sql.substring(0,sep));sql=sql.substring(sep+1);}var r=st.executeQuery(sql);check(r.next(),"expected result");return "ALLOWED predicate="+r.getBoolean(1);}catch(SQLException e){return "DENIED SQLSTATE="+e.getSQLState();}finally{c.rollback();}}catch(SQLException e){return "DENIED connect="+e.getSQLState();}}
 static void refusal(String label){try{preflight();emit(label,"PREFLIGHT_ALLOWED");}catch(IllegalStateException e){emit(label,"PREFLIGHT_DENIED "+e.getMessage());}}
 static void permissions(){
  ADMIN.execute("grant usage on schema audit to audit_r");
  emit("B.READ.direct",as("audit_r","select count(binding_token)>0 from v3_eui_version"));
  emit("B.READ.admission",as("audit_r","select count(admission_context)>0 from v3_identity_request"));
  emit("B.READ.invoker.function",as("audit_r","select v3_identity_check_unit('00000000-0000-0000-0000-000000000099',false) is null"));
  ADMIN.execute("grant select(binding_token) on v3_eui_version to public");
  emit("B.PUBLIC.column",as("audit_r","select count(binding_token)>0 from v3_eui_version"));refusal("B.PUBLIC.column");
  ADMIN.execute("revoke select(binding_token) on v3_eui_version from public");
  ADMIN.execute("grant select on v3_eui_version to public");
  emit("B.PUBLIC.table",as("audit_r","select count(binding_token)>0 from v3_eui_version"));refusal("B.PUBLIC.table");
  ADMIN.execute("revoke select on v3_eui_version from public");
  ADMIN.execute("grant select(binding_token) on v3_eui_version to audit_r");
  emit("B.READ.direct.grant",as("audit_r","select count(binding_token)>0 from v3_eui_version"));refusal("B.READ.direct.grant");
  ADMIN.execute("revoke select(binding_token) on v3_eui_version from audit_r");
  ADMIN.execute("create role audit_bridge nologin");ADMIN.execute("grant usage on schema audit to audit_bridge");ADMIN.execute("grant select(binding_token) on v3_eui_version to audit_bridge");ADMIN.execute("grant audit_bridge to audit_r");
  emit("B.READ.inherited",as("audit_r","select count(binding_token)>0 from v3_eui_version"));refusal("B.READ.inherited");
  ADMIN.execute("revoke audit_bridge from audit_r");ADMIN.execute("alter role audit_r noinherit");ADMIN.execute("grant audit_bridge to audit_r");
  emit("B.READ.NOINHERIT.direct",as("audit_r","select count(binding_token)>0 from v3_eui_version"));
  emit("B.READ.SET.ROLE",as("audit_r","set role audit_bridge; select count(binding_token)>0 from audit.v3_eui_version"));refusal("B.READ.SET.ROLE");
  ADMIN.execute("revoke audit_bridge from audit_r");ADMIN.execute("alter role audit_r inherit");ADMIN.execute("drop owned by audit_bridge");ADMIN.execute("drop role audit_bridge");
  ADMIN.execute("create view audit_token_view as select binding_token from v3_eui_version");ADMIN.execute("grant select on audit_token_view to audit_r");
  emit("B.READ.view",as("audit_r","select count(binding_token)>0 from audit_token_view"));refusal("B.READ.view");
  ADMIN.execute("create view audit_admission_view as select binding_token,admission_context from v3_identity_request");ADMIN.execute("grant select on audit_admission_view to audit_r");
  emit("B.READ.admission.view",as("audit_r","select count(binding_token)>0 and count(admission_context)>0 from audit_admission_view"));refusal("B.READ.admission.view");
  ADMIN.execute("create schema audit_aux");ADMIN.execute("grant usage on schema audit_aux to audit_r");ADMIN.execute("create function audit_aux.tokens() returns setof text language sql security definer set search_path=audit,pg_catalog as 'select binding_token from audit.v3_eui_version'");
  emit("B.READ.external.SECURITY.DEFINER",as("audit_r","select count(*)>0 from audit_aux.tokens()"));refusal("B.READ.external.SECURITY.DEFINER");
  ADMIN.execute("drop schema audit_aux cascade");ADMIN.execute("drop view audit_token_view,audit_admission_view");
  emit("B.Control.token",as("audit_w","select count(binding_token)>0 from v3_eui_version"));
  emit("B.Injector.admission.token",as("audit_i","select count(binding_token)>0 from v3_identity_request"));
  emit("B.Injector.history.token",as("audit_i","select count(binding_token)>0 from v3_eui_version"));
  emit("B.Owner.token",as("postgres","select count(binding_token)>0 from v3_eui_version"));
  emit("B.READ.history.update",as("audit_r","with changed as(update v3_eui_version set binding_token='forged' returning 1) select count(*)>0 from changed"));
  emit("B.Worker.history.update",as("audit_w","with changed as(update v3_eui_version set binding_token='forged' returning 1) select count(*)>0 from changed"));
  emit("B.Injector.history.update",as("audit_i","with changed as(update v3_eui_version set binding_token='forged' returning 1) select count(*)>0 from changed"));
  emit("B.Reactivation.history.update",as("audit_a","with changed as(update v3_eui_version set binding_token='forged' returning 1) select count(*)>0 from changed"));
  emit("B.Worker.admission",as("audit_w","with inserted as(insert into v3_identity_request select * from v3_identity_request limit 1 returning 1) select count(*)>0 from inserted"));
  ADMIN.execute("revoke usage on schema audit from audit_r");preflight();
 }
 static void bootView() throws Exception {
  ADMIN.execute("grant usage on schema audit to audit_r");ADMIN.execute("create view audit_token_view as select binding_token from v3_eui_version");ADMIN.execute("grant select on audit_token_view to audit_r");
  var props=new LinkedHashMap<String,Object>();String k="pocoma.v3.worker.";
  props.put(k+"enabled","true");props.put(k+"environment","DEV");props.put(k+"identity-authority","real");props.put(k+"identity-request","true");props.put(k+"fixture-identity","false");props.put(k+"command-enabled","false");props.put(k+"technical-probes","false");props.put(k+"worker-name","audit-identity");props.put(k+"worker-limit","4");props.put(k+"consumption-limits","IdentityRequest=4");props.put(k+"partitions","IdentityRequest=0|1|2|3");
  var server=ADMIN.queryForMap("select inet_server_addr()::text a,inet_server_port() p");
  props.put(k+"dev-targets","127.0.0.1,55439,audit_write,audit,audit_w,"+server.get("a")+","+server.get("p")+";127.0.0.1,55439,audit_read,audit,audit_r,"+server.get("a")+","+server.get("p"));
  for(boolean w:List.of(true,false)){String which=w?"write":"read",db=w?"audit_write":"audit_read",role=w?"audit_w":"audit_r";props.put("pocoma.v3.pilot."+which+".database",db);props.put("pocoma.v3.pilot."+which+".schema","audit");props.put("pocoma.v3.pilot."+which+".username",role);for(String type:List.of("business","control")){String q=k+which+"-"+type+".";props.put(q+"url","jdbc:postgresql://127.0.0.1:55439/"+db+"?currentSchema=audit");props.put(q+"username",role);props.put(q+"password","");}}
  var app=new org.springframework.boot.SpringApplication(com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication.class);app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);app.setDefaultProperties(props);
  try(var ctx=app.run("--spring.profiles.active=v3-consumption-worker-dev")){
   check(ctx.isActive(),"root inactive");emit("B.actual.Worker.with.view","STARTED; "+as("audit_r","select count(binding_token)>0 from audit_token_view"));
   var resource=ctx.getBean("v3WorkerResources");var f=resource.getClass().getDeclaredField("writeEffects");f.setAccessible(true);var tx=(SpringAttemptTransactions)f.get(resource);var pool=ctx.getBean("writeBusiness",HikariDataSource.class);var a=new JdbcIdentityAuthority(pool,tx);Object[][] seen=new Object[2][];
   for(int n=0;n<2;n++){int i=n;tx.write(()->{a.lockInCanonicalOrder(List.of(KEY));seen[i]=witness(pool);check(physicalProbe().equals("55P03"),"JPA missing physical lock");return null;});record("A.actual.JPA."+n,seen[n]);}
   check(seen[0][0]!=seen[1][0],"JPA holder reused");denied(tx,()->a.current(KEY),"A.actual.JPA.current.after.commit");
   try(var c=pool.getConnection()){TransactionSynchronizationManager.bindResource(pool,new ConnectionHolder(c));try{try{tx.write(()->{throw new AssertionError("JPA accepted prebound holder");});}catch(AttemptTransactions.Failure refused){emit("A.actual.JPA.prebound","REFUSED before callback; "+refused.getCause().getClass().getSimpleName());}}finally{TransactionSynchronizationManager.unbindResourceIfPossible(pool);}}
  }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
  finally{ADMIN.execute("drop view audit_token_view");ADMIN.execute("revoke usage on schema audit from audit_r");}
 }
 static void truncate(String role,String tables) throws Exception {try(var c=DriverManager.getConnection(URL,role,"")){c.setAutoCommit(false);try(var st=c.createStatement()){long before;try(var r=st.executeQuery("select pg_relation_size('audit.v3_identity_event')")){r.next();before=r.getLong(1);}st.execute("truncate "+tables);try(var r=st.executeQuery("select pg_relation_size('audit.v3_identity_event')")){r.next();check(before>0&&r.getLong(1)==0,"truncate did not empty physical history");emit("B."+role+".TRUNCATE","ALLOWED; nonempty event heap emptied inside transaction; explicit audit ROLLBACK follows");}}finally{c.rollback();}}}
 static void truncateCommit(String role,String table) throws Exception {long before=ADMIN.queryForObject("select count(*) from "+table,Long.class);check(before>0,"expected nonempty synthetic history");try(var c=DriverManager.getConnection(URL,role,"")){c.setAutoCommit(false);try(var st=c.createStatement()){st.execute("truncate "+table);}c.commit();}long after=ADMIN.queryForObject("select count(*) from "+table,Long.class);check(after==0,"history survived");emit("B."+role+".TRUNCATE.COMMIT",table+" rows "+before+"->"+after+"; positions remain="+ADMIN.queryForObject("select count(*) from v3_event_position",Long.class));}
 public static void main(String[] args)throws Exception{switch(args[0]){case "normal"->normal();case "retained"->retainedHolder();case "concurrent"->concurrent();case "unknown"->unknown();case "reconnect"->reconnect();case "preflight"->preflight();case "seed"->seed();case "collision"->collision();case "memory"->memory();case "permissions"->permissions();case "boot-view"->bootView();case "truncate"->truncate(args[1],args[2]);case "truncate-commit"->truncateCommit(args[1],args[2]);default->throw new IllegalArgumentException();}}
}
