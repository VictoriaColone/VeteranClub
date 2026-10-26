package cn.normanclub.admin;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.normanclub.auth.AuthMapper;
import cn.normanclub.business.AuditService;
import cn.normanclub.common.Inputs;
import cn.normanclub.security.*;
import static cn.normanclub.common.ApiException.require;
import static cn.normanclub.business.Rows.map;

@Service
public class StaffService {
    private final AuthMapper db;private final AdminMapper admin;private final AuditService audit;private final PasswordEncoder passwords;private final Clock clock;
    public StaffService(AuthMapper db,AdminMapper admin,AuditService audit,PasswordEncoder passwords,Clock clock){this.db=db;this.admin=admin;this.audit=audit;this.passwords=passwords;this.clock=clock;}
    public static void validatePassword(String password){require(password!=null&&password.length()>=8&&password.getBytes(StandardCharsets.UTF_8).length<=72,400,"密码至少 8 位且不能超过 72 字节");require(password.chars().anyMatch(Character::isLetter)&&password.chars().anyMatch(Character::isDigit),400,"密码需要同时包含字母与数字");}
    /** Initial provisioning only: never overwrites an existing account on restart. */
    @Transactional public void provisionManager(String hash){
        require(hash!=null&&hash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}"),400,"管理员初始化需要 BCrypt cost=12 哈希");
        AuthMapper.Staff existing=db.staffByUsername("norman_app_manager");
        if(existing!=null){require(existing.role.equals("admin")&&!existing.disabled,409,"管理员账号状态不正确，请使用本机维护命令处理");return;}
        AuthMapper.Staff s=new AuthMapper.Staff();s.id=Crypto.id("s_");s.username="norman_app_manager";s.name="平台管理员";s.role="admin";s.passwordHash=hash;s.createdAt=clock.millis();s.lastTotpStep=-1;
        db.insertStaff(s);audit.record("本机初始化","创建平台管理员",s.id,"最高权限账号已初始化；未记录密码或哈希");
    }
    @Transactional public Map<String,Object> create(Actor actor,Map<String,Object> body){actor.roles("admin");Inputs in=new Inputs(body,"username","name","role","password");return insert(in.text("username",3,64),in.text("name",2,60),in.text("role",1,24),in.secret("password",8,72),actor.name());}
    @Transactional public Map<String,Object> bootstrap(String username,String name,String password){require(db.adminCount()==0,409,"管理员已存在，请通过已有管理员创建员工");return insert(username,name,"admin",password,"本机初始化");}
    private Map<String,Object> insert(String username,String name,String role,String password,String actor){
        require(username.matches("[A-Za-z0-9_.-]{3,64}"),400,"员工账号格式不正确");require(Set.of("admin","customer_service","finance").contains(role),400,"员工角色不正确");validatePassword(password);
        AuthMapper.Staff s=new AuthMapper.Staff();s.id=Crypto.id("s_");s.username=username.toLowerCase(Locale.ROOT);s.name=name;s.role=role;s.passwordHash=passwords.encode(password);s.createdAt=clock.millis();s.lastTotpStep=-1;db.insertStaff(s);audit.record(actor,"创建员工",s.id,"角色："+role+"；使用账号密码登录");return view(s);
    }
    public List<Map<String,Object>> list(Actor actor){actor.roles("admin");return db.staffList().stream().map(this::view).toList();}
    public Map<String,Object> view(AuthMapper.Staff s){return map("id",s.id,"username",s.username,"name",s.name,"role",s.role,"disabled",s.disabled,"mfaEnrolled",s.totpCipher!=null,"createdAt",s.createdAt);}
    @Transactional public Map<String,Object> disable(Actor actor,String id,boolean disabled){
        actor.roles("admin");List<String> admins=admin.lockAdmins();AuthMapper.Staff s=db.lockStaff(Inputs.identifier(id));require(s!=null,404,"员工不存在");require(!s.id.equals(actor.id()),400,"不能在此停用当前登录账号");if(disabled&&s.role.equals("admin")&&!s.disabled)require(admins.size()>1,409,"至少保留一位管理员");db.disableStaff(id,disabled);db.revoke(id,"staff");db.clearChallenges(id);s.disabled=disabled;audit.record(actor.name(),disabled?"停用员工":"启用员工",id,"已撤销该员工全部会话");return view(s);
    }
    @Transactional public void resetLocally(String username,String password){validatePassword(password);AuthMapper.Staff s=db.staffByUsername(username);require(s!=null,404,"员工不存在");db.resetStaff(s.id,passwords.encode(password));db.revoke(s.id,"staff");db.clearChallenges(s.id);audit.record("本机维护","重置员工认证",s.id,"密码已重置，原会话全部撤销");}
}
