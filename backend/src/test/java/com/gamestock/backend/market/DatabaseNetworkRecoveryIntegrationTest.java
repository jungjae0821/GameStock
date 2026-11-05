package com.gamestock.backend.market;

import com.zaxxer.hikari.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:13367/.*")
class DatabaseNetworkRecoveryIntegrationTest {
    @Test void silentNetworkLossTimesOutThenOwnershipAndQueriesRecoverAutomatically()throws Exception{
        String url=System.getProperty("market.test.jdbc");
        var root=new JdbcTemplate(new DriverManagerDataSource(url,"root",""));
        String database="recovery_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE DATABASE "+database);
        try(var proxy=new NetworkProxy()){
            var config=new HikariConfig();
            config.setJdbcUrl(url.replace(":13367/",":"+proxy.port()+"/"+database));
            config.setUsername("root");config.setPassword("");config.setMaximumPoolSize(4);
            config.setConnectionTimeout(1000);config.setValidationTimeout(500);
            config.addDataSourceProperty("connectTimeout","1000");
            config.addDataSourceProperty("socketTimeout","1000");
            try(var source=new HikariDataSource(config)){
                var db=new JdbcTemplate(source);var owner=new MarketOwnership(db);
                try{
                    owner.start();owner.requireOwner();
                    try(var connection=source.getConnection()){assertEquals(1000,connection.getNetworkTimeout());}
                    long oldSession=db.queryForObject("SELECT IS_USED_LOCK(?)",Long.class,"gamestock:matching:"+database);
                    proxy.paused=true;
                    assertTimeoutPreemptively(Duration.ofSeconds(12),()->
                        assertThrows(org.springframework.dao.DataAccessException.class,()->db.queryForObject("SELECT 1",Integer.class)));
                    owner.heartbeat();assertFalse(owner.available());
                    proxy.paused=false;owner.startRecovery();
                    await().atMost(Duration.ofSeconds(10)).until(owner::available);
                    owner.requireOwner();
                    assertNotEquals(oldSession,db.queryForObject("SELECT IS_USED_LOCK(?)",Long.class,"gamestock:matching:"+database));
                    assertEquals(1,db.queryForObject("SELECT 1",Integer.class));
                }finally{proxy.paused=false;owner.close();}
            }
        }finally{root.execute("DROP DATABASE "+database);}
    }
    /** Drops bytes without closing TCP so the JDBC timeout, rather than a reset, releases work. */
    private static final class NetworkProxy implements AutoCloseable {
        private final ServerSocket listener=new ServerSocket(0,16,InetAddress.getLoopbackAddress());
        private final ExecutorService threads=Executors.newCachedThreadPool(task->{
            Thread thread=new Thread(task,"test-mysql-network");thread.setDaemon(true);return thread;
        });
        private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        volatile boolean paused;
        NetworkProxy()throws IOException{
            threads.execute(()->{
                while(!listener.isClosed())try{
                    Socket client=listener.accept();sockets.add(client);
                    Socket server=new Socket("127.0.0.1",13367);sockets.add(server);
                    threads.execute(()->forward(client,server));
                    threads.execute(()->forward(server,client));
                }catch(IOException closed){if(!listener.isClosed())throw new UncheckedIOException(closed);}
            });
        }
        int port(){return listener.getLocalPort();}
        private void forward(Socket from,Socket to){
            try{
                byte[] bytes=new byte[8192];int count;
                while((count=from.getInputStream().read(bytes))!=-1)
                    if(!paused){to.getOutputStream().write(bytes,0,count);to.getOutputStream().flush();}
            }catch(IOException disconnected){
                // JDBC closes timed-out sessions; propagate that close to release the server lock.
            }finally{closeSocket(from);closeSocket(to);}
        }
        private void closeSocket(Socket socket){sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
        public void close()throws IOException{listener.close();sockets.forEach(this::closeSocket);threads.shutdownNow();}
    }
}
