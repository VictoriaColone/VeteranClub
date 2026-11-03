package db.migration;

import java.sql.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Additive, nullable fields preserve legacy orders; admins may prepare the DDL first. */
public class V6__Order_contacts extends BaseJavaMigration {
    @Override public Integer getChecksum(){return 1;}
    @Override public void migrate(Context context)throws SQLException{
        Connection db=context.getConnection();
        String schema=db.getMetaData().getDatabaseProductName().equals("MySQL")?db.getCatalog():db.getSchema();
        add(db,schema,"contact_phone_enc",256);
        add(db,schema,"contact_wechat_enc",512);
    }
    private void add(Connection db,String schema,String field,int length)throws SQLException{
        try(PreparedStatement query=db.prepareStatement("SELECT 1 FROM information_schema.columns WHERE table_schema=? AND LOWER(table_name)='club_order' AND LOWER(column_name)=?")){
            query.setString(1,schema);query.setString(2,field);
            try(ResultSet rows=query.executeQuery()){
                if(rows.next())return;
            }
        }
        try(Statement sql=db.createStatement()){sql.execute("ALTER TABLE club_order ADD COLUMN "+field+" VARCHAR("+length+") NULL");}
    }
}
