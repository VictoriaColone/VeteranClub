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
    private final AuthService auth;private final AbuseService abuse;private final ClubConfig config;private final AccountClosureService closure;
    public AuthController(AuthService auth,AbuseService abuse,ClubConfig config,AccountClosureService closure){this.auth=auth;this.abuse=abuse;this.config=config;this.closure=closure;}
    @PostMapping("/sms/send") ApiResponse<?> send(@RequestBody Map<String,Object> body,HttpServletRequest r){Inputs in=new Inputs(body,"phone","purpose");return ApiResponse.ok(auth.sendCode(in.text("phone",11,11),abuse.ip(r),in.has("purpose")?in.text("purpose",1,16):"login"),r);}
    @PostMapping("/sms/login") ApiResponse<?> login(@RequestBody Map<String,Object> body,HttpServletRequest r,HttpServletResponse response){
        Inputs in=new Inputs(body,"phone","code");var login=auth.smsLogin(in.text("phone",11,11),in.text("code",6,6),abuse.ip(r),"mini-program".equals(r.getHeader("X-Client-Type")));cookies(login,response);return ApiResponse.ok(login.session(),r);
    }
    @PostMapping("/sms/register") ApiResponse<?> register(@RequestBody Map<String,Object> body,HttpServletRequest r,HttpServletResponse response){Inputs in=new Inputs(body,"phone","code","password");var login=auth.smsRegister(in.text("phone",11,11),in.text("code",6,6),in.secret("password",8,72),abuse.ip(r),"mini-program".equals(r.getHeader("X-Client-Type")));cookies(login,response);return ApiResponse.ok(login.session(),r);}
    @PostMapping("/password/login") ApiResponse<?> password(@RequestBody Map<String,Object> body,HttpServletRequest r,HttpServletResponse response){Inputs in=new Inputs(body,"phone","password");var login=auth.passwordLogin(in.text("phone",11,11),in.secret("password",1,72),abuse.ip(r),"mini-program".equals(r.getHeader("X-Client-Type")));cookies(login,response);return ApiResponse.ok(login.session(),r);}
    @PostMapping("/password/reset") ApiResponse<?> reset(@RequestBody Map<String,Object> body,HttpServletRequest r,HttpServletResponse response){Inputs in=new Inputs(body,"phone","code","password");auth.resetPassword(in.text("phone",11,11),in.text("code",6,6),in.secret("password",8,72),abuse.ip(r));clearUserCookies(response);return ApiResponse.ok(null,r);}
    @PostMapping("/account/close") ApiResponse<?> close(@AuthenticationPrincipal Actor actor,@RequestBody Map<String,Object> body,HttpServletRequest r,HttpServletResponse response){closure.close(actor,body,abuse.ip(r));clearUserCookies(response);return ApiResponse.ok(null,r);}
    @PostMapping("/staff/login") ApiResponse<?> staff(@RequestBody Map<String,Object> body,HttpServletRequest r,HttpServletResponse response){Inputs in=new Inputs(body,"username","password");var login=auth.staffLogin(in.text("username",3,64),in.secret("password",1,72),abuse.ip(r));cookies(login,response);return ApiResponse.ok(login.session(),r);}
    @GetMapping("/session") ApiResponse<?> session(@AuthenticationPrincipal Actor actor,HttpServletRequest r){return ApiResponse.ok(auth.current(actor),r);}
    @GetMapping("/contact") ApiResponse<?> contact(@AuthenticationPrincipal Actor actor,HttpServletRequest r){return ApiResponse.ok(auth.registrationContact(actor),r);}
    @PostMapping("/logout") ApiResponse<?> logout(@AuthenticationPrincipal Actor actor,HttpServletRequest r,HttpServletResponse response){auth.logout(actor);cookie("norman-"+actor.audience()+"-session","",true,0,response);cookie("norman-"+actor.audience()+"-csrf","",false,0,response);return ApiResponse.ok(null,r);}
    void cookies(AuthService.Login login,HttpServletResponse response){if(!login.bearer()){cookie("norman-"+login.audience()+"-session",login.token(),true,login.seconds(),response);cookie("norman-"+login.audience()+"-csrf",login.csrf(),false,login.seconds(),response);}}
    void clearUserCookies(HttpServletResponse response){cookie("norman-user-session","",true,0,response);cookie("norman-user-csrf","",false,0,response);}
    private void cookie(String name,String value,boolean httpOnly,long seconds,HttpServletResponse response){response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(name,value).httpOnly(httpOnly).secure(config.cookieSecure).sameSite("Strict").path("/").maxAge(seconds).build().toString());}
}
