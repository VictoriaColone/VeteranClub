package cn.normanclub.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.normanclub.business.*;
import cn.normanclub.config.ClubConfig;
import cn.normanclub.security.*;
import static cn.normanclub.common.ApiException.require;
import static cn.normanclub.business.Rows.map;

@Service
public class AuthService {
    public record Login(Map<String,Object> session,String token,String csrf,String audience,boolean bearer,long seconds) { }
    private final AuthMapper db;private final CatalogMapper catalog;private final Crypto crypto;private final Clock clock;
    private final SmsSender smsSender;private final AbuseService abuse;private final PasswordEncoder passwords;private final AuditService audit;private final ClubConfig config;
    private final String dummyHash;
    public AuthService(AuthMapper db,CatalogMapper catalog,Crypto crypto,Clock clock,SmsSender smsSender,AbuseService abuse,PasswordEncoder passwords,AuditService audit,ClubConfig config) {
        this.db=db;this.catalog=catalog;this.crypto=crypto;this.clock=clock;this.smsSender=smsSender;this.abuse=abuse;this.passwords=passwords;this.audit=audit;this.config=config;
        dummyHash=passwords.encode(Crypto.token());
    }
    public static String phone(String input) {require(input!=null&&input.matches("1[3-9][0-9]{9}"),400,"请输入有效的中国内地手机号");return input;}
    @Transactional
    public Map<String,Object> sendCode(String input,String ip) {
        String phone=phone(input),hash=crypto.digest("phone:"+phone);
        abuse.limit("sms-interval",hash,1,60_000);abuse.limit("sms-phone-hour",hash,5,3_600_000);abuse.limit("sms-phone-day",hash,10,86_400_000);
        abuse.limit("sms-ip-hour",ip,30,3_600_000);abuse.limit("sms-global-day","global",config.smsDailyLimit,86_400_000);
        String code=Crypto.otp();smsSender.send(phone,code);
        AuthMapper.Sms value=new AuthMapper.Sms();value.phoneHash=hash;value.codeHash=crypto.digest("otp:"+hash+":"+code);value.sentAt=clock.millis();value.expiresAt=value.sentAt+300_000;db.putSms(value);
        return map("expiresIn",300,"retryAfter",60,"delivery",config.smsProvider.equals("outbox")?"development-outbox":"sms");
    }
    @Transactional(noRollbackFor=cn.normanclub.common.ApiException.class)
    public Login smsLogin(String input,String code,String ip,boolean bearer) {
        String phone=phone(input);require(code!=null&&code.matches("[0-9]{6}"),400,"请输入六位验证码");
        abuse.limit("sms-verify-ip",ip,30,60_000);
        String hash=crypto.digest("phone:"+phone);AuthMapper.Sms value=db.sms(hash);
        boolean allowed=value!=null&&!value.consumed&&value.expiresAt>clock.millis()&&value.attempts<5;
        if(!allowed || !Crypto.equal(value.codeHash,crypto.digest("otp:"+hash+":"+code))) {
            if(value!=null){value.attempts++;if(value.attempts>=5)value.consumed=true;db.putSms(value);}abuse.loginFailed(ip);
            throw new cn.normanclub.common.ApiException(401,"验证码无效或已过期");
        }
        value.consumed=true;db.putSms(value);AuthMapper.Identity identity=db.phone(hash);AuthMapper.User user;
        if(identity==null) {
            user=new AuthMapper.User();user.id=Crypto.id("u_");user.name="用户"+phone.substring(7);user.createdAt=clock.millis();db.insertUser(user);
            identity=new AuthMapper.Identity();identity.id=Crypto.id("id_");identity.userId=user.id;identity.provider="phone";identity.appId="";identity.subjectHash=hash;identity.subjectCipher=crypto.encrypt(phone);identity.maskedValue=phone.substring(0,3)+"****"+phone.substring(7);identity.createdAt=clock.millis();db.insertIdentity(identity);
            Rows.Profile p=new Rows.Profile();p.id=Crypto.id("p_");p.userId=user.id;p.name=user.name;p.subtitle="欢迎加入诺曼部落";p.gameIds="[\"delta\",\"valorant\",\"lol\"]";p.tags="[\"新朋友\"]";p.rating=5;p.price=6800;p.color="#b6c89a";p.avatar="N";p.bio="每一场游戏，都值得认真对待。";p.createdAt=clock.millis();catalog.insertProfile(p);
        } else user=db.user(identity.userId);
        require(user!=null&&!user.disabled,403,"当前账号暂不可用，请联系管理员");
        Rows.Profile p=catalog.byUser(user.id);audit.record(user.name,"用户登录",user.id,"短信验证成功");
        return issue(user.id,user.name,"user",p.id,bearer);
    }
    @Transactional
    public Map<String,Object> staffLogin(String username,String password,String ip) {
        require(username!=null&&username.matches("[A-Za-z0-9_.-]{3,64}")&&password!=null&&password.length()<=128,401,"账号或密码不正确");
        abuse.limit("staff-ip",ip,20,60_000);abuse.limit("staff-username",username.toLowerCase(Locale.ROOT),10,300_000);
        AuthMapper.Staff staff=db.staffByUsername(username.toLowerCase(Locale.ROOT));
        boolean correct=passwords.matches(password,staff==null?dummyHash:staff.passwordHash);
        if(!correct||staff==null||staff.disabled){abuse.loginFailed(ip);throw new cn.normanclub.common.ApiException(401,"账号或密码不正确");}
        // Lock the account so concurrent password steps cannot leave multiple enrollment secrets valid.
        staff=db.lockStaff(staff.id);db.clearChallenges(staff.id);
        String token=Crypto.token(),secret=staff.totpCipher==null?Totp.secret():null;
        AuthMapper.Challenge challenge=new AuthMapper.Challenge();challenge.tokenHash=Crypto.sha256(token);challenge.staffId=staff.id;challenge.setupCipher=secret==null?null:crypto.encrypt(secret);challenge.expiresAt=clock.millis()+300_000;db.insertChallenge(challenge);
        return map("step",secret==null?"totp":"enroll","challengeToken",token,"expiresIn",300,"secret",secret,
            "otpauthUri",secret==null?null:"otpauth://totp/"+URLEncoder.encode("Norman Club:"+staff.username,StandardCharsets.UTF_8)+"?secret="+secret+"&issuer=Norman%20Club&algorithm=SHA1&digits=6&period=30");
    }
    @Transactional(noRollbackFor=cn.normanclub.common.ApiException.class)
    public Login verifyStaff(String token,String code,String ip) {
        require(token!=null&&token.matches("[A-Za-z0-9_-]{43}"),401,"验证会话无效或已过期");
        abuse.limit("totp-ip",ip,30,60_000);AuthMapper.Challenge c=db.challenge(Crypto.sha256(token));
        require(c!=null&&!c.consumed&&c.expiresAt>clock.millis()&&c.attempts<5,401,"验证会话无效或已过期");
        AuthMapper.Staff staff=db.lockStaff(c.staffId);require(staff!=null&&!staff.disabled,401,"账号暂不可用");
        String secret=crypto.decrypt(c.setupCipher==null?staff.totpCipher:c.setupCipher);
        long step=Totp.verify(secret,code,clock.millis(),staff.lastTotpStep);
        if(step<0){c.attempts++;if(c.attempts>=5)c.consumed=true;db.updateChallenge(c);abuse.loginFailed(ip);throw new cn.normanclub.common.ApiException(401,"动态验证码不正确或已使用");}
        db.totp(staff.id,crypto.encrypt(secret),step);c.consumed=true;db.updateChallenge(c);
        audit.record(staff.name,"员工登录",staff.id,c.setupCipher==null?"密码与动态验证码验证成功":"已绑定动态验证器并登录");
        return issue(staff.id,staff.name,staff.role,null,false);
    }
    Login issue(String id,String name,String role,String playerId,boolean bearer) {
        boolean staff=!role.equals("user");String audience=staff?"staff":"user",token=Crypto.token(),csrf=Crypto.token();long seconds=staff?28_800:604_800;
        AuthMapper.Session s=new AuthMapper.Session();s.tokenHash=Crypto.sha256(token);s.accountId=id;s.audience=audience;s.csrfHash=Crypto.sha256(csrf);s.bearer=bearer;s.createdAt=clock.millis();s.lastSeenAt=s.createdAt;s.expiresAt=s.createdAt+seconds*1000;db.insertSession(s);
        return new Login(map("token",bearer?token:"","userId",id,"name",name,"role",role,"playerId",playerId,"expiresAt",s.expiresAt),token,csrf,audience,bearer,seconds);
    }
    public Actor authenticate(String token,String audience,boolean bearer) {
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))return null;
        AuthMapper.Session s=db.session(Crypto.sha256(token));if(s==null||!s.audience.equals(audience)||s.bearer!=bearer)return null;
        if(s.expiresAt<=clock.millis()||(audience.equals("staff")&&clock.millis()-s.lastSeenAt>1_800_000)){db.logout(s.tokenHash);return null;}
        Actor actor;
        if(audience.equals("staff")){
            AuthMapper.Staff staff=db.staff(s.accountId);if(staff==null||staff.disabled||bearer)return null;
            actor=new Actor(staff.id,staff.name,staff.role,null,s.tokenHash,audience,false);
        }else{
            AuthMapper.User user=db.user(s.accountId);if(user==null||user.disabled)return null;Rows.Profile p=catalog.byUser(user.id);
            actor=new Actor(user.id,user.name,"user",p==null?null:p.id,s.tokenHash,audience,bearer);
        }
        if(clock.millis()-s.lastSeenAt>=30_000)db.touch(s.tokenHash,clock.millis());return actor;
    }
    public void csrf(Actor actor,String csrf) {
        AuthMapper.Session s=db.session(actor.tokenHash());require(s!=null&&csrf!=null&&Crypto.equal(s.csrfHash,Crypto.sha256(csrf)),403,"请求校验已失效，请刷新页面后重试");
    }
    public Map<String,Object> current(Actor actor) {return map("token","","userId",actor.id(),"name",actor.name(),"role",actor.role(),"playerId",actor.playerId());}
    public void logout(Actor actor){db.logout(actor.tokenHash());}
}
