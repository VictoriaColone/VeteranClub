package cn.normanclub;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import db.migration.V4__Per_game_applications;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualificationMigrationTest {
    @Test void legacyActiveApplicationsAreSplitButHistoryRemainsIntact()throws Exception{
        try(Connection db=DriverManager.getConnection("jdbc:h2:mem:migration_"+UUID.randomUUID()+";MODE=MySQL")){
            try(Statement s=db.createStatement()){
                s.execute("CREATE TABLE qualification_application(id VARCHAR(48) PRIMARY KEY,user_id VARCHAR(48),player_id VARCHAR(48),name VARCHAR(60),service_type VARCHAR(12),game_ids VARCHAR(500),introduction VARCHAR(500),status VARCHAR(16),active_key VARCHAR(120) UNIQUE,created_at BIGINT,score INT,review_note VARCHAR(500),reviewed_at BIGINT)");
                s.execute("INSERT INTO qualification_application VALUES('legacy','u1','p1','测试用户','hourly','[\"delta\",\"valorant\"]','保留原有说明','assessment','u1:hourly',123,80,'资料审核通过',124)");
                s.execute("INSERT INTO qualification_application VALUES('single','u1','p1','测试用户','escort','[\"delta\"]','已有单项申请','pending','u1:escort',125,NULL,NULL,NULL)");
                s.execute("INSERT INTO qualification_application VALUES('history','u1','p1','测试用户','escort','[\"delta\",\"lol\"]','历史已通过申请','approved',NULL,100,90,'历史考核记录',101)");
            }
            Context context=mock(Context.class);when(context.getConnection()).thenReturn(db);new V4__Per_game_applications().migrate(context);
            Map<String,String> active=new HashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM qualification_application")){
                int count=0;while(r.next()){count++;if(r.getString("status").equals("approved")){assertEquals("history",r.getString("id"));assertEquals("[\"delta\",\"lol\"]",r.getString("game_ids"));}
                    else {active.put(r.getString("active_key"),r.getString("game_ids"));if(r.getString("service_type").equals("hourly")){assertEquals("assessment",r.getString("status"));assertEquals("保留原有说明",r.getString("introduction"));assertEquals(123,r.getLong("created_at"));assertTrue(r.getString("id").length()<=48);}}
                }assertEquals(4,count);
            }
            assertEquals(Set.of("u1:hourly:delta","u1:hourly:valorant","u1:escort:delta"),active.keySet());assertEquals("[\"delta\"]",active.get("u1:hourly:delta"));assertEquals("[\"valorant\"]",active.get("u1:hourly:valorant"));
            new V4__Per_game_applications().migrate(context);try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM qualification_application")){r.next();assertEquals(4,r.getInt(1));}
        }
    }
}
