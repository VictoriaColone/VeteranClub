package cn.normanclub;

import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserIdentityMigrationTest {
    @Test void administratorPreparedSchemaDoesNotRequireRepeatedDdlOrInventIdentityData()throws Exception{
        String url="jdbc:h2:mem:identity_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try(Connection db=DriverManager.getConnection(url,"sa","");Statement sql=db.createStatement()){
            var source=new org.springframework.jdbc.datasource.SingleConnectionDataSource(db,true);
            Flyway.configure().dataSource(source).target("7").load().migrate();
            sql.execute("INSERT INTO club_user(id,name,created_at) VALUES('legacy','测试用户',1),('other','其他用户',1)");
            String ddl=Files.readString(Path.of("../deploy/rds/user-identity-v8.sql")).replaceAll("(?m)^--.*$","");
            for(String statement:ddl.split(";"))if(!statement.isBlank())sql.execute(statement);
            assertEquals(1,Flyway.configure().dataSource(source).target("8").load().migrate().migrationsExecuted);
            assertEquals(0,Flyway.configure().dataSource(source).target("8").load().migrate().migrationsExecuted);
            try(ResultSet row=sql.executeQuery("SELECT identity_submitted_at,id_number_hash,password_hash,closed_at FROM club_user WHERE id='legacy'")){
                assertTrue(row.next());for(int i=1;i<=4;i++)assertNull(row.getObject(i));
            }
            sql.execute("UPDATE club_user SET id_number_hash='synthetic-hash' WHERE id='legacy'");
            assertThrows(SQLException.class,()->sql.execute("UPDATE club_user SET id_number_hash='synthetic-hash' WHERE id='other'"));
        }
    }
}
