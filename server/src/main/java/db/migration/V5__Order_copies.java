package db.migration;

import java.sql.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** DDL may be prepared by a database administrator when the app account has no ALTER grant. */
public class V5__Order_copies extends BaseJavaMigration {
    @Override public Integer getChecksum(){return 1;}

    @Override public void migrate(Context context)throws SQLException{
        Connection db=context.getConnection();
        String schema=db.getMetaData().getDatabaseProductName().equals("MySQL")?db.getCatalog():db.getSchema();
        if(!exists(db,"SELECT 1 FROM information_schema.columns WHERE table_schema=? AND LOWER(table_name)='club_order' AND LOWER(column_name)='copies'",schema)){
            try(Statement sql=db.createStatement()){sql.execute("ALTER TABLE club_order ADD COLUMN copies INT NOT NULL DEFAULT 1");}
        }
        if(!exists(db,"SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=? AND LOWER(table_name)='club_order' AND LOWER(constraint_name)='ck_order_copies' AND constraint_type='CHECK'",schema)){
            try(Statement sql=db.createStatement()){sql.execute("ALTER TABLE club_order ADD CONSTRAINT ck_order_copies CHECK(copies BETWEEN 1 AND 99)");}
        }
    }

    private static boolean exists(Connection db,String query,String schema)throws SQLException{
        try(PreparedStatement sql=db.prepareStatement(query)){
            sql.setString(1,schema);
            try(ResultSet rows=sql.executeQuery()){return rows.next();}
        }
    }
}
