package db.migration;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** An administrator may pre-create the schema; a DML-only runtime then verifies it. */
public class V9__Member_wallet extends BaseJavaMigration {
    @Override public Integer getChecksum(){return 1;}
    @Override public void migrate(Context context)throws Exception{
        var db=context.getConnection();boolean mysql=db.getMetaData().getDatabaseProductName().equals("MySQL");String schema=mysql?db.getCatalog():db.getSchema();
        String source;try(var input=getClass().getResourceAsStream("/db/schema/member-wallet-v9.sql")){source=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);}
        for(String part:source.split(";")){
            String sql=part.strip();if(sql.isEmpty())continue;
            if(sql.startsWith("CREATE TABLE ")){
                String table=sql.substring(13,sql.indexOf('(')).strip();boolean exists;
                try(var q=db.prepareStatement("SELECT 1 FROM information_schema.tables WHERE table_schema=? AND LOWER(table_name)=?")){q.setString(1,schema);q.setString(2,table);try(var rows=q.executeQuery()){exists=rows.next();}}
                if(!exists)try(var statement=db.createStatement()){statement.execute(sql);}
                // Fail startup if an administrator supplied an incomplete schema.
                var columns=new ArrayList<String>();for(String line:sql.substring(sql.indexOf('(')+1).split("\n")){String value=line.strip();if(value.matches("[a-z_]+ (VARCHAR|BIGINT|BOOLEAN).*"))columns.add(value.split(" ")[0]);}
                try(var statement=db.createStatement();var ignored=statement.executeQuery("SELECT "+String.join(",",columns)+" FROM "+table+" WHERE 1=0")){}
                var expected=new ArrayList<Set<String>>();
                for(String line:sql.substring(sql.indexOf('(')+1).split("\n")){String value=line.strip();if(value.matches("[a-z_]+ .*PRIMARY KEY,?"))expected.add(Set.of(value.split(" ")[0]));else if(value.startsWith("UNIQUE (")||value.startsWith("PRIMARY KEY ("))expected.add(new HashSet<>(Arrays.stream(value.substring(value.indexOf('(')+1,value.indexOf(')')).split(",")).map(String::strip).toList()));}
                var actual=new HashMap<String,Set<String>>();try(var indexes=db.getMetaData().getIndexInfo(mysql?schema:null,mysql?null:schema,table,true,false)){while(indexes.next()){String name=indexes.getString("INDEX_NAME"),column=indexes.getString("COLUMN_NAME");if(name!=null&&column!=null)actual.computeIfAbsent(name,k->new HashSet<>()).add(column.toLowerCase(Locale.ROOT));}}
                for(var unique:expected)if(!actual.containsValue(unique))throw new SQLException("Required unique constraint missing in "+table+": "+unique);

            }else{
                String name=sql.split(" ")[2],table=sql.substring(sql.indexOf(" ON ")+4,sql.indexOf('('));boolean exists=false;
                try(var indexes=db.getMetaData().getIndexInfo(mysql?schema:null,mysql?null:schema,table,false,false)){while(indexes.next())if(name.equalsIgnoreCase(indexes.getString("INDEX_NAME")))exists=true;}
                if(!exists)try(var statement=db.createStatement()){statement.execute(sql);}
            }
        }
    }
}
