package cn.normanclub.auth;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cn.normanclub.common.ApiResponse;
import cn.normanclub.security.Actor;

@RestController
@RequestMapping("/v1/account/identity")
public class UserIdentityController {
    private final UserIdentityService identities;
    public UserIdentityController(UserIdentityService identities){this.identities=identities;}
    @GetMapping ApiResponse<?> current(@AuthenticationPrincipal Actor actor,HttpServletRequest request){return ApiResponse.ok(identities.current(actor),request);}
    @PostMapping ApiResponse<?> submit(@AuthenticationPrincipal Actor actor,@RequestBody Map<String,Object> body,HttpServletRequest request){return ApiResponse.ok(identities.submit(actor,body),request);}
}
