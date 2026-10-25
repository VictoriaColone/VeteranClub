package cn.normanclub.acceptance;

import java.time.Clock;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import cn.normanclub.auth.*;
import cn.normanclub.business.*;
import cn.normanclub.business.Rows.*;
import cn.normanclub.security.Crypto;

@Component
public class AcceptanceSeed implements ApplicationRunner {
    private final AuthMapper auth;private final CatalogMapper catalog;private final Clock clock;private final PasswordEncoder passwords;private final TransactionTemplate tx;
    public AcceptanceSeed(AcceptanceGuard guard,AuthMapper auth,CatalogMapper catalog,Clock clock,PasswordEncoder passwords,PlatformTransactionManager manager){this.auth=auth;this.catalog=catalog;this.clock=clock;this.passwords=passwords;tx=new TransactionTemplate(manager);}
    public void run(ApplicationArguments args){tx.executeWithoutResult(status->{
        user("u-boss","p0","林序",List.of());
        user("u-hourly","p-hourly","小屿",List.of("hourly"));
        user("u-escort","p-escort","凛",List.of("escort"));
        user("u-player","p1","岚",List.of("hourly","escort"));
        user("u-p7","p7","远山",List.of("hourly","escort"));
        for(String role:List.of("admin","customer_service","finance")){
            String id="acceptance-"+role;if(auth.staff(id)!=null)continue;
            AuthMapper.Staff s=new AuthMapper.Staff();s.id=id;s.username=id;s.name=switch(role){case "admin"->"验收管理员";case "finance"->"验收财务";default->"验收客服";};
            s.role=role;s.passwordHash=passwords.encode(Crypto.token());s.lastTotpStep=-1;s.createdAt=clock.millis();auth.insertStaff(s);
        }
    });}
    private void user(String uid,String pid,String name,List<String> types){
        if(auth.user(uid)!=null)return;
        AuthMapper.User u=new AuthMapper.User();u.id=uid;u.name=name;u.createdAt=clock.millis();auth.insertUser(u);
        Profile p=new Profile();p.id=pid;p.userId=uid;p.name=name;p.subtitle="内部验收测试账号";p.gameIds="[\"delta\",\"valorant\",\"lol\"]";p.tags="[\"验收样例\"]";p.rating=5;p.price=6800;p.color="#b6c89a";p.avatar=name.substring(0,1);p.available=!types.isEmpty();p.approved=!types.isEmpty();p.bio="仅供内部验收，不代表真实用户。";p.createdAt=clock.millis();catalog.insertProfile(p);
        for(String type:types)for(String game:List.of("delta","valorant","lol")){
            Qualification q=new Qualification();q.playerId=pid;q.serviceType=type;q.gameId=game;q.grantedAt=clock.millis();catalog.grant(q);
            Product product=new Product();product.id="acceptance-"+pid+"-"+type+"-"+game;product.userId=uid;product.playerId=pid;product.sellerName=name;product.gameId=game;product.serviceType=type;product.title=type.equals("escort")?"任务护航 · 验收样例":"时长组队 · 验收样例";product.description="服务器数据库中的内部验收商品";product.price=type.equals("escort")?12800:6800;product.task=type.equals("escort")?"100w纯带出":"";product.active=true;product.createdAt=clock.millis();catalog.insertProduct(product);
        }
    }
}
