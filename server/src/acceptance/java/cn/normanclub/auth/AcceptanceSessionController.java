package cn.normanclub.auth;

import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import cn.normanclub.acceptance.AcceptanceGuard;
import cn.normanclub.business.*;
import cn.normanclub.common.*;
import static cn.normanclub.common.ApiException.require;

@RestController
public class AcceptanceSessionController {
    private final AcceptanceGuard guard;private final AuthService auth;private final AuthMapper db;private final CatalogMapper catalog;private final AuthController cookies;private final AuditService audit;
    public AcceptanceSessionController(AcceptanceGuard guard,AuthService auth,AuthMapper db,CatalogMapper catalog,AuthController cookies,AuditService audit){this.guard=guard;this.auth=auth;this.db=db;this.catalog=catalog;this.cookies=cookies;this.audit=audit;}
    // Uses an already-public auth path; absent from a normal production JAR.
    @PostMapping("/v1/auth/sms/login/acceptance") @Transactional
    public ApiResponse<?> login(@RequestBody Map<String,Object> body,HttpServletRequest request,HttpServletResponse response){
        guard.check(request);Inputs in=new Inputs(body,"role","accountId");String role=in.text("role",1,24);
        AuthService.Login login;
        if(role.equals("user")||role.equals("player")){
            String id=in.optional("accountId",48);if(id.isEmpty())id=role.equals("player")?"u-player":"u-boss";
            require(Set.of("u-boss","u-hourly","u-escort","u-player","u-p7").contains(id),403,"只能切换验收账号");
            var user=db.user(id);require(user!=null&&!user.disabled,403,"验收账号不可用");login=auth.issue(id,user.name,"user",catalog.byUser(id).id,false);
        }else{
            throw new ApiException(403,"管理后台请使用账号密码登录");
        }
        audit.record("验收环境","切换测试身份",String.valueOf(login.session().get("userId")),"仅服务器本机入口，不使用短信或验证器");cookies.cookies(login,response);return ApiResponse.ok(login.session(),request);
    }
}
