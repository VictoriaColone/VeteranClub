package cn.normanclub.business;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import cn.normanclub.business.Rows.*;
import cn.normanclub.security.*;
import cn.normanclub.common.*;
import static cn.normanclub.common.ApiException.require;
import static cn.normanclub.business.Rows.map;

@Service
public class CatalogService {
    private final CatalogMapper db;private final OrderMapper orders;private final AuditService audit;private final JsonMapper json;private final Clock clock;private final PresenceUpdates updates;
    public CatalogService(CatalogMapper db,OrderMapper orders,AuditService audit,JsonMapper json,Clock clock,PresenceUpdates updates){this.db=db;this.orders=orders;this.audit=audit;this.json=json;this.clock=clock;this.updates=updates;}
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter presence(Actor actor){return updates.subscribe(actor);}
    public void statusChanged(){updates.changed();}
    public static final List<Map<String,Object>> GAMES=List.of(
        map("id","delta","name","三角洲","short","D","color","#90c6aa","tagline","明确目标，默契撤离","regions",List.of("国服")),
        map("id","valorant","name","无畏契约","short","V","color","#fa6c76","tagline","默契配合，漂亮拿下","regions",List.of("国服 · 华东","国服 · 华南","国服 · 华北")),
        map("id","lol","name","英雄联盟","short","L","color","#d7ad65","tagline","下一局，一起翻盘","regions",List.of("艾欧尼亚","峡谷之巅","黑色玫瑰")));
    public static Map<String,Object> game(String id){return GAMES.stream().filter(g->g.get("id").equals(id)).findFirst().orElseThrow(()->new ApiException(400,"请选择有效游戏"));}
    public static String type(String value){require(Set.of("hourly","escort").contains(value),400,"请选择组队或护航类型");return value;}
    public List<String> strings(String data){return json.readValue(data,new TypeReference<List<String>>(){});}
    public String encode(Object data){return json.writeValueAsString(data);}
    public boolean qualified(Profile p,String type,String game){return p!=null&&p.approved&&db.qualified(p.id,type,game)>0;}
    public Profile self(Actor actor){actor.user();Profile p=db.byUser(actor.id());require(p!=null,404,"用户档案不存在");return p;}
    public Map<String,Object> profileView(Profile p){return profileView(p,db.qualifications(p.id),orders.busy(p.id)!=null);}
    private Map<String,Object> profileView(Profile p,List<Qualification> q,boolean busy){
        Map<String,List<String>> directions=q.stream().collect(Collectors.groupingBy(v->v.serviceType,LinkedHashMap::new,Collectors.mapping(v->v.gameId,Collectors.toList())));
        return map("id",p.id,"userId",p.userId,"name",p.name,"subtitle",p.subtitle,"gameIds",strings(p.gameIds),"tags",strings(p.tags),"rating",p.rating,"completed",p.completed,"price",p.price,"color",p.color,"avatar",p.avatar,"available",p.available,"busy",busy,"approved",p.approved,"qualifications",new ArrayList<>(directions.keySet()),"qualificationGames",directions,"bio",p.bio);
    }
    public List<Map<String,Object>> profiles(Actor actor,String game){
        if(game!=null)game(game);Map<String,List<Qualification>> q=db.allQualifications().stream().collect(Collectors.groupingBy(v->v.playerId));Set<String> busy=new HashSet<>(orders.busyPlayers());
        return db.profiles().stream().filter(p->actor.staff()||p.approved||p.userId.equals(actor.id())).filter(p->game==null||strings(p.gameIds).contains(game)).map(p->profileView(p,q.getOrDefault(p.id,List.of()),busy.contains(p.id))).toList();
    }
    public List<Product> products(Actor actor,boolean mine){
        if(mine){actor.user();return db.ownProducts(actor.id());}
        Map<String,Profile> profiles=db.profiles().stream().collect(Collectors.toMap(p->p.id,p->p));Set<String> qualified=db.allQualifications().stream().map(q->q.playerId+":"+q.serviceType+":"+q.gameId).collect(Collectors.toSet());
        return db.publicProducts().stream().filter(p->"club".equals(p.sellerType)||(profiles.containsKey(p.playerId)&&profiles.get(p.playerId).approved&&qualified.contains(p.playerId+":"+p.serviceType+":"+p.gameId))).toList();
    }
    @Transactional public Product publish(Actor actor,Map<String,Object> body){
        Profile p=db.lockProfile(self(actor).id);Inputs in=new Inputs(body,"gameId","serviceType","title","description","price","task");String game=in.text("gameId",1,24),type=type(in.text("serviceType",1,12));game(game);require(qualified(p,type,game),403,"请先通过对应类型与游戏的考核");
        Product item=new Product();item.id=Crypto.id("SP_");item.userId=actor.id();item.playerId=p.id;item.sellerName=p.name;item.gameId=game;item.serviceType=type;item.title=in.text("title",2,40);item.description=in.text("description",5,200);item.price=in.integer("price",100,100000);item.task=type.equals("escort")?in.text("task",1,200):"";item.active=true;item.createdAt=clock.millis();db.insertProduct(item);audit.record(actor.name(),"发布商品",item.id,item.title);return item;
    }
    @Transactional public Product active(Actor actor,String id,Map<String,Object> body){
        actor.user();Profile p=db.lockProfile(self(actor).id);Product item=db.lockProduct(Inputs.identifier(id));require(item!=null,404,"商品不存在");require(actor.id().equals(item.userId),403,"只能管理自己的商品");boolean active=new Inputs(body,"active").bool("active");if(active)require(qualified(p,item.serviceType,item.gameId),403,"上架需要对应的考核资格");db.productActive(id,active);item.active=active;audit.record(actor.name(),active?"上架商品":"下架商品",id,item.title);return item;
    }
    public Map<String,Object> applicationView(Application a){return map("id",a.id,"userId",a.userId,"playerId",a.playerId,"name",a.name,"serviceType",a.serviceType,"gameIds",strings(a.gameIds),"introduction",a.introduction,"status",a.status,"score",a.score,"reviewNote",a.reviewNote,"createdAt",a.createdAt,"reviewedAt",a.reviewedAt);}
    public List<Map<String,Object>> applications(Actor a){return db.applications(a.staff(),a.id()).stream().map(this::applicationView).toList();}
    @Transactional public Map<String,Object> apply(Actor actor,Map<String,Object> body){
        Profile p=db.lockProfile(self(actor).id);Inputs in=new Inputs(body,"serviceType","gameIds","introduction");String type=type(in.text("serviceType",1,12));List<String> games=in.strings("gameIds",1);String gameId=games.getFirst();game(gameId);
        require(db.qualified(p.id,type,gameId)==0,409,"该游戏的该类型已取得资格");
        require(db.applications(false,actor.id()).stream().noneMatch(a->a.serviceType.equals(type)&&Set.of("pending","assessment").contains(a.status)&&strings(a.gameIds).contains(gameId)),409,"该游戏该类型已有申请正在审核或考核中");
        Application a=new Application();a.id=Crypto.id("AP_");a.userId=actor.id();a.playerId=p.id;a.name=p.name;a.serviceType=type;a.gameIds=encode(games);a.introduction=in.text("introduction",10,500);a.status="pending";a.activeKey=actor.id()+":"+type+":"+gameId;a.createdAt=clock.millis();db.insertApplication(a);audit.record(actor.name(),"提交入驻申请",a.id,game(gameId).get("name")+" · "+(type.equals("escort")?"护航资格":"组队资格"));return applicationView(a);
    }
    @Transactional public Map<String,Object> review(Actor actor,String id,Map<String,Object> body){
        actor.roles("admin");Inputs in=new Inputs(body,"status","score","note");String status=in.text("status",1,16),note=in.text("note",2,500);require(Set.of("assessment","approved","rejected").contains(status),400,"审核操作无效");
        Application a=db.lockApplication(Inputs.identifier(id));require(a!=null,404,"申请不存在");require(Set.of("pending","assessment").contains(a.status),409,"该申请已处理");require(strings(a.gameIds).size()==1,409,"考核申请需按游戏分别处理");
        Profile p=db.lockProfile(a.playerId);require(p!=null,404,"用户档案不存在");
        if(status.equals("assessment"))require(a.status.equals("pending"),409,"已进入专项考核");
        if(status.equals("approved")){
            require(a.status.equals("assessment"),409,"需先通过资料审核");a.score=in.integer("score",60,100);
            for(String game:strings(a.gameIds)){Qualification q=new Qualification();q.playerId=p.id;q.serviceType=a.serviceType;q.gameId=game;q.grantedAt=clock.millis();db.grant(q);}
            p.approved=true;List<String> games=new ArrayList<>(strings(p.gameIds));games.addAll(strings(a.gameIds));p.gameIds=encode(games.stream().distinct().toList());db.updateProfile(p);
        }else if(in.has("score"))a.score=in.integer("score",0,100);
        a.status=status;a.reviewNote=note;a.reviewedAt=clock.millis();if(!status.equals("assessment"))a.activeKey=null;db.updateApplication(a);audit.record(actor.name(),"审核入驻申请",a.id,note);updates.changed();return applicationView(a);
    }
    @Transactional public Map<String,Object> availability(Actor actor,Map<String,Object> body){Profile p=db.lockProfile(self(actor).id);p.available=new Inputs(body,"available").bool("available");require(p.approved&&!db.qualifications(p.id).isEmpty(),403,"请先通过入驻考核");if(p.available)require(orders.busy(p.id)==null,409,"当前有未完成服务，完成后自动恢复接单中");db.updateProfile(p);updates.changed();audit.record(actor.name(),"接单状态",p.id,p.available?"接单中":"服务中");return profileView(p);}
    @Transactional public Map<String,Object> updateProfile(Actor actor,String id,Map<String,Object> body){
        actor.roles("admin");Inputs in=new Inputs(body,"available","approved","price");Profile p=db.lockProfile(Inputs.identifier(id));require(p!=null,404,"成员不存在");
        if(in.has("approved")){require(!in.bool("approved"),400,"必须通过入驻考核授予资格");p.approved=false;p.available=false;db.revoke(p.id);}
        if(in.has("available")){p.available=in.bool("available");if(p.available)require(p.approved,403,"尚未取得接单资格");}if(in.has("price"))p.price=in.integer("price",100,100000);db.updateProfile(p);updates.changed();audit.record(actor.name(),"更新成员",p.id,"资格、接单状态或服务单价已更新");return profileView(p);
    }
}
