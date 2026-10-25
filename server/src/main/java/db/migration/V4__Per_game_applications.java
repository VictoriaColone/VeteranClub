package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Split legacy in-flight batches; completed history and granted qualifications stay intact. */
public class V4__Per_game_applications extends BaseJavaMigration {
    @Override public Integer getChecksum(){return 1;}
    @Override public void migrate(Context context)throws Exception{
        Connection db=context.getConnection();JsonMapper json=JsonMapper.builder().build();
        record Pending(String id,String user,String player,String name,String type,List<String> games,String intro,String status,long created,Integer score,String note,Long reviewed){}
        List<Pending> pending=new ArrayList<>();
        try(Statement sql=db.createStatement();ResultSet r=sql.executeQuery("SELECT * FROM qualification_application WHERE status IN ('pending','assessment')")){
            while(r.next()){
                List<String> games=json.readValue(r.getString("game_ids"),new TypeReference<List<String>>(){}).stream().distinct().toList();
                if(games.isEmpty()||!Set.of("delta","valorant","lol").containsAll(games))throw new IllegalStateException("Invalid legacy application games");
                pending.add(new Pending(r.getString("id"),r.getString("user_id"),r.getString("player_id"),r.getString("name"),r.getString("service_type"),games,r.getString("introduction"),r.getString("status"),r.getLong("created_at"),(Integer)r.getObject("score"),r.getString("review_note"),(Long)r.getObject("reviewed_at")));
            }
        }
        for(Pending a:pending)for(int i=0;i<a.games.size();i++){
            String game=a.games.get(i),key=a.user+":"+a.type+":"+game,gameIds=json.writeValueAsString(List.of(game));
            if(i==0){try(PreparedStatement sql=db.prepareStatement("UPDATE qualification_application SET game_ids=?,active_key=? WHERE id=?")){sql.setString(1,gameIds);sql.setString(2,key);sql.setString(3,a.id);sql.executeUpdate();}}
            else try(PreparedStatement sql=db.prepareStatement("INSERT INTO qualification_application(id,user_id,player_id,name,service_type,game_ids,introduction,status,active_key,created_at,score,review_note,reviewed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")){
                String id="AP_"+UUID.nameUUIDFromBytes((a.id+":"+game).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
                Object[] fields={id,a.user,a.player,a.name,a.type,gameIds,a.intro,a.status,key,a.created,a.score,a.note,a.reviewed};
                for(int n=0;n<fields.length;n++)sql.setObject(n+1,fields[n]);sql.executeUpdate();
            }
        }
    }
}
