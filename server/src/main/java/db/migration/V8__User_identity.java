package db.migration;

import java.sql.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Allows an administrator to pre-create DDL before a DML-only application is deployed. */
public class V8__User_identity extends BaseJavaMigration {
    @Override public Integer getChecksum(){return 1;}
    @Override public void migrate(Context context)throws SQLException{
        Connection db=context.getConnection();boolean mysql=db.getMetaData().getDatabaseProductName().equals("MySQL");
        String schema=mysql?db.getCatalog():db.getSchema();
        add(db,schema,"real_name_cipher","TEXT");add(db,schema,"id_number_cipher","TEXT");
        add(db,schema,"id_number_hash","VARCHAR(64)");add(db,schema,"identity_submitted_at","BIGINT");
        add(db,schema,"identity_consent_version","VARCHAR(24)");add(db,schema,"closed_at","BIGINT");
        add(db,schema,"password_hash","VARCHAR(200)");
        try(PreparedStatement query=db.prepareStatement("SELECT 1 FROM information_schema.columns WHERE table_schema=? AND LOWER(table_name)='sms_code' AND LOWER(column_name)='purpose'")){
            query.setString(1,schema);try(ResultSet rows=query.executeQuery()){if(!rows.next())try(Statement sql=db.createStatement()){sql.execute("ALTER TABLE sms_code ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'login'");}}
        }
        boolean found=false;
        try(ResultSet indexes=db.getMetaData().getIndexInfo(mysql?schema:null,mysql?null:schema,"club_user",true,false)){
            while(indexes.next())if("uk_user_id_number".equalsIgnoreCase(indexes.getString("INDEX_NAME")))found=true;
        }
        if(!found)try(Statement sql=db.createStatement()){sql.execute("CREATE UNIQUE INDEX uk_user_id_number ON club_user(id_number_hash)");}
    }
    private void add(Connection db,String schema,String column,String definition)throws SQLException{
        try(PreparedStatement query=db.prepareStatement("SELECT 1 FROM information_schema.columns WHERE table_schema=? AND LOWER(table_name)='club_user' AND LOWER(column_name)=?")){
            query.setString(1,schema);query.setString(2,column);try(ResultSet rows=query.executeQuery()){if(rows.next())return;}
        }
        try(Statement sql=db.createStatement()){sql.execute("ALTER TABLE club_user ADD COLUMN "+column+" "+definition);}
    }
}
