package db.migration;

import java.sql.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Additive fields; existing orders retain legacy commission and income allocations. */
public class V7__Member_commission extends BaseJavaMigration {
    @Override public Integer getChecksum(){return 1;}
    @Override public void migrate(Context context)throws SQLException{
        Connection db=context.getConnection();
        String schema=db.getMetaData().getDatabaseProductName().equals("MySQL")?db.getCatalog():db.getSchema();
        add(db,schema,"service_profile","commission_bps","INT NOT NULL DEFAULT 2000");
        add(db,schema,"club_order","member_commission","BOOLEAN NOT NULL DEFAULT FALSE");
        add(db,schema,"order_member","commission_bps","INT NULL");
        constraint(db,schema,"service_profile","ck_profile_commission","commission_bps BETWEEN 0 AND 10000");
        constraint(db,schema,"order_member","ck_member_commission","commission_bps IS NULL OR commission_bps BETWEEN 0 AND 10000");
    }
    private boolean exists(Connection db,String query,String schema,String table,String name)throws SQLException{
        try(PreparedStatement sql=db.prepareStatement(query)){sql.setString(1,schema);sql.setString(2,table);sql.setString(3,name);try(ResultSet rows=sql.executeQuery()){return rows.next();}}
    }
    private void add(Connection db,String schema,String table,String name,String definition)throws SQLException{
        if(!exists(db,"SELECT 1 FROM information_schema.columns WHERE table_schema=? AND LOWER(table_name)=? AND LOWER(column_name)=?",schema,table,name)){
            try(Statement sql=db.createStatement()){sql.execute("ALTER TABLE "+table+" ADD COLUMN "+name+" "+definition);}
        }
    }
    private void constraint(Connection db,String schema,String table,String name,String expression)throws SQLException{
        if(!exists(db,"SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=? AND LOWER(table_name)=? AND LOWER(constraint_name)=? AND constraint_type='CHECK'",schema,table,name)){
            try(Statement sql=db.createStatement()){sql.execute("ALTER TABLE "+table+" ADD CONSTRAINT "+name+" CHECK("+expression+")");}
        }
    }
}
