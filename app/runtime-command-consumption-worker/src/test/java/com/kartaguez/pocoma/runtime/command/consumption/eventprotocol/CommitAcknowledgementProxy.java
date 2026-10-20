package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only plain PostgreSQL wire proxy. AFTER_COMMIT consumes the server's real
 * COMMIT/ReadyForQuery reply without sending it to JDBC, then breaks TCP. */
final class CommitAcknowledgementProxy implements AutoCloseable {
    enum Fault { NONE, BEFORE_COMMIT, AFTER_COMMIT }
    final AtomicReference<Fault> armed=new AtomicReference<>(Fault.NONE);
    final AtomicInteger intercepted=new AtomicInteger(), committedReplies=new AtomicInteger();
    final ServerSocket listener;
    final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    final ExecutorService tasks=Executors.newVirtualThreadPerTaskExecutor();
    CommitAcknowledgementProxy(String host,int port) throws IOException {
        listener=new ServerSocket(0,50,InetAddress.getLoopbackAddress());
        tasks.submit(()->{while(!listener.isClosed()) {
            try {var client=listener.accept();var server=new Socket(host,port);
                client.setSoTimeout(15000);server.setSoTimeout(15000);sockets.add(client);sockets.add(server);
                var discard=new AtomicBoolean();tasks.submit(()->upstream(client,server,discard));
                tasks.submit(()->downstream(server,client,discard));
            } catch(IOException e) {if(!listener.isClosed())throw new UncheckedIOException(e);}
        }});
    }
    int localPort(){return listener.getLocalPort();}
    void upstream(Socket client,Socket server,AtomicBoolean discard) {
        try {
            var in=new DataInputStream(client.getInputStream());var out=new DataOutputStream(server.getOutputStream());
            int startup=in.readInt();out.writeInt(startup);out.write(in.readNBytes(startup-4));out.flush();
            while(true) {
                int type=in.readUnsignedByte(),length=in.readInt();byte[] body=in.readNBytes(length-4);
                if(body.length!=length-4)throw new EOFException();
                if(type=='Q' && new String(body,StandardCharsets.UTF_8).replace("\0","").trim().equalsIgnoreCase("COMMIT")) {
                    var fault=armed.getAndSet(Fault.NONE);if(fault!=Fault.NONE)intercepted.incrementAndGet();
                    if(fault==Fault.BEFORE_COMMIT)return;
                    if(fault==Fault.AFTER_COMMIT)discard.set(true);
                }
                out.writeByte(type);out.writeInt(length);out.write(body);out.flush();
            }
        } catch(IOException e) { /* Actual TCP failure is observed by JDBC. */ }
        finally {close(client);close(server);}
    }
    void downstream(Socket server,Socket client,AtomicBoolean discard) {
        boolean committed=false;
        try {
            var in=new DataInputStream(server.getInputStream());var out=new DataOutputStream(client.getOutputStream());
            while(true) {
                int type=in.readUnsignedByte(),length=in.readInt();byte[] body=in.readNBytes(length-4);
                if(body.length!=length-4)throw new EOFException();
                if(discard.get()) {
                    if(type=='C' && new String(body,StandardCharsets.UTF_8).startsWith("COMMIT"))committed=true;
                    if(type=='Z') {if(committed)committedReplies.incrementAndGet();return;}
                } else {out.writeByte(type);out.writeInt(length);out.write(body);out.flush();}
            }
        } catch(IOException e) { /* No Java exception substituted for a commit result. */ }
        finally {close(client);close(server);}
    }
    static void close(Socket s){try{s.close();}catch(IOException ignored){}}
    @Override public void close() throws IOException {listener.close();sockets.forEach(CommitAcknowledgementProxy::close);tasks.shutdownNow();}
}
