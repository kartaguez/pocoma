// Disposable measurement receipt, not an application component or canonical test.
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.kartaguez.pocoma.domain.pot.v3.Fraction;
import com.kartaguez.pocoma.engine.read.pot.v3.ReadPotV3Result.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class Calibration {
    static final ObjectMapper JSON = new ObjectMapper();
    static final com.sun.management.ThreadMXBean THREAD =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final int CAP = 4 * 1024 * 1024;
    static volatile Object sink;
    static {
        var module = new SimpleModule();
        module.addSerializer(Fraction.class, new JsonSerializer<Fraction>() {
            public void serialize(Fraction f, JsonGenerator g, SerializerProvider p) throws IOException {
                g.writeStartObject(); g.writeStringField("n", f.numerator().toString());
                g.writeStringField("d", f.denominator().toString()); g.writeEndObject();
            }
        });
        module.addSerializer(LocalDate.class, new JsonSerializer<LocalDate>() {
            public void serialize(LocalDate d, JsonGenerator g, SerializerProvider p) throws IOException {
                g.writeString(d.toString());
            }
        });
        JSON.registerModule(module);
    }
    static Map<String,Object> obj(Object... pairs) {
        var m = new LinkedHashMap<String,Object>();
        for (int i=0; i<pairs.length; i+=2) m.put((String)pairs[i], pairs[i+1]);
        return m;
    }
    static UUID id(int n) { return new UUID(0, n); }
    static long allocated() { return THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    static Fraction fraction(int digits, int i) {
        // Consecutive integers: exact, coprime and nonzero, no precision truncation.
        var n = BigInteger.TEN.pow(digits-1).add(BigInteger.valueOf(i+1));
        return new Fraction(n, n.add(BigInteger.ONE));
    }
    static String label(boolean escaped, int i) {
        return (escaped ? "é漢🙂\"\\\n\t".repeat(32) : "Expense ") + i;
    }
    static ExpenseRef ref(int i, boolean wide) {
        return new ExpenseRef(id(i+10000),wide?Long.MAX_VALUE:1,wide?Long.MAX_VALUE:2,wide?Long.MAX_VALUE:3,false);
    }
    static List<ExpenseRef> refs(int count, boolean wide) {
        var list = new ArrayList<ExpenseRef>();
        for (int i=0;i<count;i++) list.add(ref(i,wide));
        return list;
    }
    static Object pot(int count, int holders, int digits, boolean escaped, boolean wide) {
        var shares = new ArrayList<Shareholder>();
        var balances = new LinkedHashMap<UUID,Fraction>();
        for (int i=0;i<holders;i++) {
            shares.add(new Shareholder(id(i+1),1,2,3,false,true,label(escaped,i),fraction(digits,i)));
            // Balanced synthetic vector, paired +/- equal values, no user identities.
            var f=fraction(digits,i/2);
            balances.put(id(i+1),i%2==0?f:new Fraction(f.numerator().negate(),f.denominator()));
        }
        return obj("value",new Pot(id(9000),label(escaped,0),1,2,shares,balances,refs(count,wide)),"servedVersion",Long.MAX_VALUE);
    }
    static Object batch(int count,int participants,int digits,boolean escaped) {
        var list=new ArrayList<Expense>();
        for(int i=0;i<count;i++) {
            var ps=new ArrayList<Participation>();
            for(int j=0;j<participants;j++) ps.add(new Participation(id(j+1),Optional.of(fraction(digits,i+j))));
            list.add(new Expense(ref(i,true),label(escaped,i),LocalDate.of(2026,11,4),fraction(digits,i),id(1),ps));
        }
        // Map Optional to explicit nullable values only in measurement DTO, not default Jackson Optional.
        var dto=new ArrayList<Object>();
        for(var e:list) {
            var ps=new ArrayList<Object>();
            for(var p:e.participants()) ps.add(obj("shareholderId",p.shareholderId(),"specificWeight",p.specificWeight().orElse(null)));
            dto.add(obj("reference",e.reference(),"label",e.label(),"date",e.date(),"amount",e.amount(),"payerId",e.payerId(),"participants",ps));
        }
        return obj("value",dto,"servedVersion",Long.MAX_VALUE);
    }
    static class Capped extends OutputStream {
        final byte[] buffer; int size;
        Capped(int cap) { buffer=new byte[cap]; }
        public void write(int b) throws IOException {
            if(size==buffer.length) throw new IOException("measurement capacity exceeded");
            buffer[size++]=(byte)b;
        }
        public void write(byte[] b,int off,int len) throws IOException {
            if(len>buffer.length-size) throw new IOException("measurement capacity exceeded");
            System.arraycopy(b,off,buffer,size,len); size+=len;
        }
    }
    static Map<String,Object> measure(String name,Supplier<Object> factory) throws Exception {
        long a=allocated(), t=System.nanoTime(); Object value=factory.get();
        long constructNs=System.nanoTime()-t, constructAlloc=allocated()-a;
        for(int i=0;i<3;i++) sink=JSON.writeValueAsBytes(value);
        var samples=new ArrayList<Object>(); byte[] bytes=null;
        for(int i=0;i<5;i++) {
            a=allocated(); t=System.nanoTime(); long c=THREAD.getCurrentThreadCpuTime();
            bytes=JSON.writeValueAsBytes(value);
            samples.add(obj("wall_ns",System.nanoTime()-t,"cpu_ns",THREAD.getCurrentThreadCpuTime()-c,"allocated_bytes",allocated()-a));
            sink=bytes;
        }
        // Encode into an isolated fixed buffer; this is not the future HTTP serializer.
        a=allocated(); t=System.nanoTime(); var capped=new Capped(CAP); boolean overflow=false;
        try { JSON.writeValue(capped,value); } catch(IOException expected) {
            if(!expected.getMessage().contains("measurement capacity exceeded")) throw expected;
            overflow=true;
        }
        long capNs=System.nanoTime()-t, capAlloc=allocated()-a;
        if(!overflow && !Arrays.equals(bytes,Arrays.copyOf(capped.buffer,capped.size))) throw new AssertionError("encoded bytes differ");
        return obj("profile",name,"classification","MEASURED_SYNTHETIC","utf8_bytes",bytes.length,
            "construction_wall_ns",constructNs,"construction_allocated_bytes",constructAlloc,"serialization_samples",samples,
            "capped_bytes_retained",capped.size,"capped_overflow",overflow,"capped_wall_ns",capNs,"capped_allocated_bytes",capAlloc);
    }
    public static void main(String[] args) throws Exception {
        THREAD.setThreadAllocatedMemoryEnabled(true); THREAD.setThreadCpuTimeEnabled(true);
        var rows=new ArrayList<Object>();
        for(int n:new int[]{0,200,2000}) {
            int h=n==2000?100:20;
            rows.add(measure("pot-"+n,()->pot(n,h,3,false,false)));
            rows.add(measure("pot-"+n+"-wide-utf8-r1024",()->pot(n,h,1024,true,true)));
            rows.add(measure("manifest-"+n+"-wide",()->obj("expenses",refs(n,true))));
        }
        for(int n:new int[]{49,50,51,85,86}) {
            rows.add(measure("batch-"+n+"-p20-r3",()->batch(n,20,3,false)));
            rows.add(measure("input-batch-"+n,()->obj("potId",id(9000),"potVersion",Long.MAX_VALUE,"references",refs(n,true))));
        }
        rows.add(measure("batch-50-p20-r256-utf8",()->batch(50,20,256,true)));
        rows.add(measure("batch-50-p100-r1024-utf8",()->batch(50,100,1024,true)));
        rows.add(measure("balance-100-r1024",()->((Pot)((Map<?,?>)pot(0,100,1024,false,true)).get("value")).balances()));
        for(int n:new int[]{20,100,101}) rows.add(measure("navigation-"+n,()->obj("value",java.util.stream.IntStream.range(1,n+1).mapToObj(Calibration::id).toList(),"nextCursor","v1_"+"A".repeat(64))));
        rows.add(measure("binding-own",()->obj("euiVersion",Long.MAX_VALUE,"active",true,"bindingToken",id(1).toString())));
        rows.add(measure("command-result",()->obj("requestId",id(2),"operation","UPDATE_EXPENSE_FINANCIALS","outcome","COMPLETED","writeOutcome","CHANGED","resultingPotVersion",Long.MAX_VALUE)));
        rows.add(measure("identity-result",()->obj("requestId",id(3),"operation","REGISTRATION","outcome","COMPLETED","euiVersion",Long.MAX_VALUE)));
        rows.add(measure("error",()->obj("code","SERVICE_UNAVAILABLE","requestId",id(4))));
        int overhead=JSON.writeValueAsBytes(obj("payload","")).length;
        for(int delta:new int[]{-1,0,1}) rows.add(measure("byte-boundary-"+delta,()->obj("payload","x".repeat(CAP+delta-overhead))));
        // Concurrent encodings only: no claim of JDBC/quota/HTTP concurrency qualification.
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<Map<String,Object>>>();
            for(int i=0;i<2;i++) futures.add(executor.submit(()->{
                var value=pot(2000,100,1024,true,true); barrier.await(10,TimeUnit.SECONDS);
                long a=allocated(),t=System.nanoTime(); var b=JSON.writeValueAsBytes(value);
                return obj("utf8_bytes",b.length,"allocated_bytes",allocated()-a,"wall_ns",System.nanoTime()-t);
            }));
            var concurrent=new ArrayList<Object>();
            for(var f:futures) concurrent.add(f.get(30,TimeUnit.SECONDS));
            rows.add(obj("profile","two-concurrent-encodings","classification","MEASURED_SYNTHETIC","threads",concurrent));
        }
        var runtime=Runtime.getRuntime();
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(obj("profiles",rows,
            "heap_max_bytes",runtime.maxMemory(),"heap_used_at_end_bytes",runtime.totalMemory()-runtime.freeMemory(),
            "caveat","Thread allocation is cumulative allocation, not retained/peak heap. No HTTP, SQL, quota or SLO proof.")));
    }
}
