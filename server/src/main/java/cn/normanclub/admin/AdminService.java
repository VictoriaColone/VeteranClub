package cn.normanclub.admin;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.normanclub.auth.AuthMapper;
import cn.normanclub.business.*;
import cn.normanclub.business.Rows.*;
import cn.normanclub.common.Inputs;
import cn.normanclub.jobs.TaskMapper;
import cn.normanclub.security.*;
import static cn.normanclub.common.ApiException.require;
import static cn.normanclub.business.Rows.map;

@Service
public class AdminService {
    private final AdminMapper db;private final AuthMapper auth;private final OrderMapper orders;private final CatalogMapper catalog;private final LedgerMapper ledger;private final OrderViews views;private final AuditService audit;private final Clock clock;private final SecurityMapper security;private final AbuseService abuse;private final TaskMapper tasks;
    public AdminService(AdminMapper db,AuthMapper auth,OrderMapper orders,CatalogMapper catalog,LedgerMapper ledger,OrderViews views,AuditService audit,Clock clock,SecurityMapper security,AbuseService abuse,TaskMapper tasks){this.db=db;this.auth=auth;this.orders=orders;this.catalog=catalog;this.ledger=ledger;this.views=views;this.audit=audit;this.clock=clock;this.security=security;this.abuse=abuse;this.tasks=tasks;}
    public List<AdminMapper.UserView> users(Actor a){a.roles("admin","finance","customer_service");return db.users();}
    @Transactional public void disableUser(Actor a,String id,boolean disabled){a.roles("admin");require(db.disable(Inputs.identifier(id),disabled)==1,404,"用户不存在");auth.revoke(id,"user");audit.record(a.name(),disabled?"停用用户":"启用用户",id,"用户会话已撤销");}
    public List<Map<String,Object>> settlements(Actor a){a.roles("admin","finance");return ledger.incomes(null).stream().map(OrderViews::income).toList();}
    public List<Audit> audit(Actor a){a.roles("admin");return audit.list();}
    private LocalDate date(long value){return Instant.ofEpochMilli(value).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();}
    private int net(Order o){return o.paidAt==null?0:o.amount-(o.refundStatus.equals("refunded")&&o.refundAmount!=null?o.refundAmount:0);}
    public Map<String,Object> dashboard(Actor a){
        a.roles("admin","finance","customer_service");List<Profile> members=catalog.profiles().stream().filter(p->p.approved).toList();LocalDate today=date(clock.millis());Set<String> busy=new HashSet<>(orders.busyPlayers());
        long start=today.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();DashboardTotals totals=orders.dashboardTotals(start,start+86_400_000);
        List<Map<String,Object>> trend=new ArrayList<>();
        for(int i=6;i>=0;i--){LocalDate d=today.minusDays(i);long from=d.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();DayTotals day=orders.dayTotals(from,from+86_400_000);trend.add(map("label",d.toString().substring(5).replace('-','/'),"revenue",day.revenue,"orders",day.orders));}
        List<Map<String,Object>> distribution=CatalogService.GAMES.stream().map(g->map("name",g.get("name"),"color",g.get("color"),"orders",orders.paidGameCount((String)g.get("id")))).toList();
        WalletTotals amounts=ledger.totals(null);
        return map("todayRevenue",totals.todayRevenue,"todayOrders",totals.todayOrders,"liveOrders",totals.liveOrders,"refundPending",totals.refundPending,"onlinePlayers",members.stream().filter(p->p.available).count(),"totalPlayers",members.size(),"availablePlayers",members.stream().filter(p->p.available&&!busy.contains(p.id)).count(),"unassigned",totals.unassigned,"pendingPayout",amounts.total-amounts.paid,"revenueTrend",trend,"gameDistribution",distribution,"recentOrders",orders.recent().stream().map(views::view).toList(),"activity",audit.list().stream().limit(6).toList());
    }

    public List<SecurityMapper.Ban> bans(Actor a){a.roles("admin");return security.bans();}
    @Transactional public void ban(Actor a,Map<String,Object> body,String currentIp){a.roles("admin");Inputs in=new Inputs(body,"ip","reason","minutes");String ip=AbuseService.literal(in.text("ip",2,45));require(!ip.equals(currentIp),400,"不能封禁当前管理连接的 IP");String reason=in.text("reason",2,200);abuse.ban(ip,reason,clock.millis()+in.integer("minutes",1,10080)*60_000L,a.name());audit.record(a.name(),"封禁 IP",ip,reason);}
    @Transactional public void unban(Actor a,String ip){a.roles("admin");String value=AbuseService.literal(ip);security.revoke(value);audit.record(a.name(),"解除 IP 封禁",value,"管理员手动解除");}
    public List<Task> problems(Actor a){a.roles("admin");return tasks.problems();}
}
