package cn.normanclub;

import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemberWalletMigrationTest {
    @Test void precreatedSchemaIsVerifiedWithoutImportingHistoricalIncome()throws Exception{
        String url="jdbc:h2:mem:wallet_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try(var db=DriverManager.getConnection(url,"sa","");var sql=db.createStatement()){
            var source=new org.springframework.jdbc.datasource.SingleConnectionDataSource(db,true);Flyway.configure().dataSource(source).target("8").load().migrate();
            String ddl=Files.readString(Path.of("../deploy/rds/member-wallet-v9.sql")).replaceAll("(?m)^--.*$","");for(String statement:ddl.split(";"))if(!statement.isBlank())sql.execute(statement);
            assertEquals(1,Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);assertEquals(0,Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM wallet_income")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            assertThrows(SQLException.class,()->sql.execute("INSERT INTO wallet_income(income_id,order_id,player_id,fund_mode,amount,frozen,paid,release_at) VALUES('x','o','p','real',100,60,60,1)"));
        }
    }
}
