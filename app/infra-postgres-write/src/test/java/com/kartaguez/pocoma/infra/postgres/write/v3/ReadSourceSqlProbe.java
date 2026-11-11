package com.kartaguez.pocoma.infra.postgres.write.v3;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
/** SQL text only, never bound values/tokens; instrumentation for grouped-source assertions. */
final class ReadSourceSqlProbe extends DelegatingDataSource {
    final List<String> statements=new ArrayList<>();
    ReadSourceSqlProbe(DataSource delegate) { super(delegate); }
    @Override public Connection getConnection() throws SQLException {
        var connection=super.getConnection();
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,args) -> {
            if(m.getName().equals("prepareStatement")) statements.add((String)args[0]);
            try { return m.invoke(connection,args); } catch(InvocationTargetException error) { throw error.getCause(); }
        });
    }
}
