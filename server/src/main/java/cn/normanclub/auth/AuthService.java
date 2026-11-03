package cn.normanclub.auth;

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
        return sendCode(input,ip,"login");
    }
    @Transactional
    public Map<String,Object> sendCode(String input,String ip,String purpose) {
        require(Set.of("login","register","reset","close").contains(purpose),400,"验证码用途不正确");
        String phone=phone(input),hash=crypto.digest("phone:"+phone);
        abuse.limit("sms-interval",hash,1,60_000);abuse.limit("sms-phone-hour",hash,5,3_600_000);abuse.limit("sms-phone-day",hash,10,86_400_000);
        abuse.limit("sms-ip-hour",ip,30,3_600_000);abuse.limit("sms-global-day","global",config.smsDailyLimit,86_400_000);
        String code=Crypto.otp();smsSender.send(phone,code);
        AuthMapper.Sms value=new AuthMapper.Sms();value.phoneHash=hash;value.purpose=purpose;value.codeHash=crypto.digest("otp:"+hash+":"+purpose+":"+code);value.sentAt=clock.millis();value.expiresAt=value.sentAt+300_000;db.putSms(value);
        return map("expiresIn",300,"retryAfter",60,"delivery",config.smsProvider.equals("outbox")?"development-outbox":"sms");
    }
    @Transactional(noRollbackFor=cn.normanclub.common.ApiException.class)
    public Login smsLogin(String input,String code,String ip,boolean bearer) {
        return phoneSession(input,code,ip,bearer,false,null);
    }
    @Transactional(noRollbackFor=cn.normanclub.common.ApiException.class)
    public Login smsRegister(String input,String code,String password,String ip,boolean bearer) {
        validateUserPassword(password);return phoneSession(input,code,ip,bearer,true,password);
    }
    String consumeCode(String input,String code,String ip,String purpose) {
        String phone=phone(input);require(code!=null&&code.matches("[0-9]{6}"),400,"请输入六位验证码");
        abuse.limit("sms-verify-ip",ip,30,60_000);
        String hash=crypto.digest("phone:"+phone);AuthMapper.Sms value=db.sms(hash);
        boolean allowed=value!=null&&purpose.equals(value.purpose)&&!value.consumed&&value.expiresAt>clock.millis()&&value.attempts<5;
        if(!allowed || !Crypto.equal(value.codeHash,crypto.digest("otp:"+hash+":"+purpose+":"+code))) {
            if(value!=null){value.attempts++;if(value.attempts>=5)value.consumed=true;db.putSms(value);}abuse.loginFailed(ip);
            throw new cn.normanclub.common.ApiException(401,"验证码无效或已过期");
        }
        value.consumed=true;db.putSms(value);return phone;
    }
    private Login phoneSession(String input,String code,String ip,boolean bearer,boolean register,String password) {
        String phone=consumeCode(input,code,ip,register?"register":"login"),hash=crypto.digest("phone:"+phone);
        AuthMapper.Identity identity=db.phone(hash);AuthMapper.User user;
        require(!register||identity==null,409,"该手机号已注册，请直接短信登录；重新注册需先注销原账号");
        require(register||identity!=null,404,"该手机号尚未注册，请切换到注册");
        if(register) {
            user=new AuthMapper.User();user.id=Crypto.id("u_");user.name="用户"+phone.substring(7);user.createdAt=clock.millis();db.insertUser(user);
            db.userPasswordUpdate(user.id,passwords.encode(password));
            identity=new AuthMapper.Identity();identity.id=Crypto.id("id_");identity.userId=user.id;identity.provider="phone";identity.appId="";identity.subjectHash=hash;identity.subjectCipher=crypto.encrypt(phone);identity.maskedValue=phone.substring(0,3)+"****"+phone.substring(7);identity.createdAt=clock.millis();db.insertIdentity(identity);
            Rows.Profile p=new Rows.Profile();p.id=Crypto.id("p_");p.userId=user.id;p.name=user.name;p.subtitle="欢迎加入诺曼部落";p.gameIds="[\"delta\",\"valorant\",\"lol\"]";p.tags="[\"新朋友\"]";p.rating=5;p.price=6800;p.color="#b6c89a";p.avatar="N";p.bio="每一场游戏，都值得认真对待。";p.createdAt=clock.millis();catalog.insertProfile(p);
        } else user=db.user(identity.userId);
        require(user!=null&&!user.disabled,403,"当前账号暂不可用，请联系管理员");
        Rows.Profile p=catalog.byUser(user.id);audit.record(user.name,register?"用户注册":"用户登录",user.id,"短信验证成功");
        return issue(user.id,user.name,"user",p.id,bearer);
    }
    public static void validateUserPassword(String password){
        require(password!=null&&password.length()>=8&&password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72&&password.chars().anyMatch(Character::isLetter)&&password.chars().anyMatch(Character::isDigit),400,"密码需至少8位，包含字母和数字，且不超过72字节");
    }
    @Transactional public Login passwordLogin(String input,String password,String ip,boolean bearer){
        String phone=phone(input);require(password!=null&&password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72,401,"手机号或密码不正确");
        abuse.limit("user-password-ip",ip,20,60_000);abuse.limit("user-password-phone",crypto.digest("phone:"+phone),10,300_000);
        var identity=db.phone(crypto.digest("phone:"+phone));var user=identity==null?null:db.user(identity.userId);String hash=user==null?null:db.userPassword(user.id);
        boolean matches=passwords.matches(password,hash==null?dummyHash:hash);
        require(matches&&hash!=null&&user!=null&&!user.disabled,401,"手机号或密码不正确");
        // A locking read sees the latest hash even under MySQL REPEATABLE READ after a concurrent reset.
        user=db.lockUser(user.id);require(!user.disabled&&Crypto.equal(hash,db.latestUserPassword(user.id)),401,"手机号或密码不正确");
        var profile=catalog.byUser(user.id);audit.record(user.name,"用户登录",user.id,"密码验证成功");return issue(user.id,user.name,"user",profile.id,bearer);
    }
    @Transactional(noRollbackFor=cn.normanclub.common.ApiException.class)
    public void resetPassword(String input,String code,String password,String ip){
        validateUserPassword(password);String phone=consumeCode(input,code,ip,"reset");var identity=db.phone(crypto.digest("phone:"+phone));
        require(identity!=null,404,"手机号尚未注册，请先注册");var user=db.lockUser(identity.userId);require(user!=null&&!user.disabled,403,"当前账号暂不可用，请联系平台");
        db.userPasswordUpdate(user.id,passwords.encode(password));db.revoke(user.id,"user");audit.record(user.name,"重置用户密码",user.id,"短信验证成功；原用户会话已全部撤销");
    }
    @Transactional
    public Login staffLogin(String username,String password,String ip) {
        require(username!=null&&username.matches("[A-Za-z0-9_.-]{3,64}")&&password!=null&&password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72,401,"账号或密码不正确");
        String normalized=username.toLowerCase(Locale.ROOT);
        abuse.limit("staff-ip",ip,20,60_000);abuse.limit("staff-username",normalized,10,300_000);
        AuthMapper.Staff staff=db.staffByUsername(normalized);
        String verifiedHash=staff==null?dummyHash:staff.passwordHash;
        boolean correct=passwords.matches(password,verifiedHash);
        if(!correct||staff==null||staff.disabled){abuse.loginFailed(ip);throw new cn.normanclub.common.ApiException(401,"账号或密码不正确");}
        staff=db.lockStaff(staff.id);require(staff!=null&&!staff.disabled&&Crypto.equal(verifiedHash,staff.passwordHash),401,"账号或密码不正确");
        db.clearChallenges(staff.id);
        audit.record(staff.name,"管理员登录",staff.id,"账号密码验证成功");
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
    public Map<String,Object> registrationContact(Actor actor) {
        actor.user();AuthMapper.Identity identity=db.phoneByUser(actor.id());
        return map("userId",actor.id(),"phone",identity==null?"":crypto.decrypt(identity.subjectCipher));
    }
    public void logout(Actor actor){db.logout(actor.tokenHash());}
}
