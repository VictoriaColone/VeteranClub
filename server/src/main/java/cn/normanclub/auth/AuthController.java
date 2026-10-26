package cn.normanclub.auth;

import java.util.Map;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cn.normanclub.common.*;
import cn.normanclub.config.ClubConfig;
import cn.normanclub.security.*;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final AuthService auth;private final AbuseService abuse;private final ClubConfig config;
    public AuthController(AuthService auth,AbuseService abuse,ClubConfig config){this.auth=auth;this.abuse=abuse;this.config=config;}
    @PostMapping("/sms/send") ApiResponse<?> send(@RequestBody Map<String,Object> body,HttpServletRequest r){Inputs in=new Inputs(body,"phone");return ApiResponse.ok(auth.sendCode(in.text("phone",11,11),abuse.ip(r)),r);}
    @PostMapping("/sms/login") ApiResponse<?> login(@RequestBody Map<String,Object> body,HttpServletRequest r,HttpServletResponse response){
        Inputs in=new Inputs(body,"phone","code");var login=auth.smsLogin(in.text("phone",11,11),in.text("code",6,6),abuse.ip(r),"mini-program".equals(r.getHeader("X-Client-Type")));cookies(login,response);return ApiResponse.ok(login.session(),r);
    }
    @PostMapping("/staff/login") ApiResponse<?> staff(@RequestBody Map<String,Object> body,HttpServletRequest r,HttpServletResponse response){Inputs in=new Inputs(body,"username","password");var login=auth.staffLogin(in.text("username",3,64),in.secret("password",1,72),abuse.ip(r));cookies(login,response);return ApiResponse.ok(login.session(),r);}
    @GetMapping("/session") ApiResponse<?> session(@AuthenticationPrincipal Actor actor,HttpServletRequest r){return ApiResponse.ok(auth.current(actor),r);}
    @PostMapping("/logout") ApiResponse<?> logout(@AuthenticationPrincipal Actor actor,HttpServletRequest r,HttpServletResponse response){auth.logout(actor);cookie("norman-"+actor.audience()+"-session","",true,0,response);cookie("norman-"+actor.audience()+"-csrf","",false,0,response);return ApiResponse.ok(null,r);}
    void cookies(AuthService.Login login,HttpServletResponse response){if(!login.bearer()){cookie("norman-"+login.audience()+"-session",login.token(),true,login.seconds(),response);cookie("norman-"+login.audience()+"-csrf",login.csrf(),false,login.seconds(),response);}}
    private void cookie(String name,String value,boolean httpOnly,long seconds,HttpServletResponse response){response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(name,value).httpOnly(httpOnly).secure(config.cookieSecure).sameSite("Strict").path("/").maxAge(seconds).build().toString());}
}
