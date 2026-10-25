package cn.normanclub.security;

import java.net.InetAddress;
import java.time.Clock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import cn.normanclub.config.ClubConfig;
import cn.normanclub.business.AuditService;
import static cn.normanclub.common.ApiException.require;

@Service
public class AbuseService {
    private final SecurityMapper db; private final Crypto crypto; private final Clock clock; private final ClubConfig config;private final TransactionTemplate limits;private final AuditService audit;
    public AbuseService(SecurityMapper db,Crypto crypto,Clock clock,ClubConfig config,PlatformTransactionManager manager,AuditService audit) { this.db=db;this.crypto=crypto;this.clock=clock;this.config=config;this.audit=audit;limits=new TransactionTemplate(manager);limits.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); }
    public boolean allow(String purpose,String identity,int max,long windowMillis) {
        return Boolean.TRUE.equals(limits.execute(status->{
            long now=clock.millis(); String key=purpose+":"+crypto.digest(identity);
            db.ensureBucket(key,now+windowMillis); SecurityMapper.Bucket bucket=db.bucket(key);
            int hits=bucket.windowEnd<=now?1:bucket.hits+1; long end=bucket.windowEnd<=now?now+windowMillis:bucket.windowEnd;
            db.bucketUpdate(key,Math.min(hits,max+1),end); return hits<=max;
        }));
    }
    public void limit(String purpose,String identity,int max,long windowMillis) { require(allow(purpose,identity,max,windowMillis),429,"操作过于频繁，请稍后再试"); }
    public void ban(String ip,String reason,long until,String actor) {
        limits.executeWithoutResult(status->{SecurityMapper.Ban ban=new SecurityMapper.Ban();ban.ip=literal(ip);ban.reason=reason;ban.expiresAt=until;ban.createdAt=clock.millis();ban.actor=actor;db.ban(ban);audit.record(actor,"封禁 IP",ban.ip,reason);});
    }
    public void loginFailed(String ip) { if(!allow("login-fail",ip,20,900_000))ban(ip,"短时间内多次验证失败",clock.millis()+1_800_000,"系统"); }
    public String ip(HttpServletRequest request) {
        String peer=literal(request.getRemoteAddr());
        if(config.trustedProxies.stream().map(AbuseService::literal).anyMatch(peer::equals)) {
            String real=request.getHeader("X-Real-IP"); if(real!=null)return literal(real);
        }
        return peer; // X-Forwarded-For is deliberately ignored.
    }
    public void check(String ip) { require(db.activeBan(ip,clock.millis())==null,403,"当前网络访问暂受限制，请稍后重试或联系管理员"); }
    public static String literal(String ip) {
        require(ip!=null && ip.length()<=45 && ip.matches("[0-9a-fA-F:.]+"),400,"IP 地址格式不正确");
        if(!ip.contains(":")){
            require(ip.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"),400,"请输入 IP 字面地址，不能使用主机名");
            for(String octet:ip.split("\\."))require(Integer.parseInt(octet)<=255,400,"IP 地址格式不正确");
        }
        try { String value=InetAddress.getByName(ip).getHostAddress();return value.equals("0:0:0:0:0:0:0:1")?"::1":value; }
        catch(Exception ex) { throw new cn.normanclub.common.ApiException(400,"IP 地址格式不正确"); }
    }
}
