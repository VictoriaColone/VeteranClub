package cn.normanclub.admin;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cn.normanclub.security.*;
import cn.normanclub.common.*;

@RestController
@RequestMapping("/v1/admin")
public class AdminController {
    private final AdminService admin;private final StaffService staff;private final AbuseService abuse;
    public AdminController(AdminService admin,StaffService staff,AbuseService abuse){this.admin=admin;this.staff=staff;this.abuse=abuse;}
    @GetMapping("/users") ApiResponse<?> users(@AuthenticationPrincipal Actor a,HttpServletRequest r){return ApiResponse.ok(admin.users(a),r);}
    @PatchMapping("/users/{id}") ApiResponse<?> user(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){admin.disableUser(a,id,new Inputs(body,"disabled").bool("disabled"));return ApiResponse.ok(null,r);}
    @GetMapping("/dashboard") ApiResponse<?> dashboard(@AuthenticationPrincipal Actor a,HttpServletRequest r){return ApiResponse.ok(admin.dashboard(a),r);}
    @GetMapping("/settlements") ApiResponse<?> settlements(@AuthenticationPrincipal Actor a,HttpServletRequest r){return ApiResponse.ok(admin.settlements(a),r);}
    @GetMapping("/audit") ApiResponse<?> audit(@AuthenticationPrincipal Actor a,HttpServletRequest r){return ApiResponse.ok(admin.audit(a),r);}
    @GetMapping("/staff") ApiResponse<?> staff(@AuthenticationPrincipal Actor a,HttpServletRequest r){return ApiResponse.ok(staff.list(a),r);}
    @PostMapping("/staff") ApiResponse<?> createStaff(@AuthenticationPrincipal Actor a,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(staff.create(a,body),r);}
    @PatchMapping("/staff/{id}") ApiResponse<?> staff(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(staff.disable(a,id,new Inputs(body,"disabled").bool("disabled")),r);}
    @GetMapping("/security/ip-bans") ApiResponse<?> bans(@AuthenticationPrincipal Actor a,HttpServletRequest r){return ApiResponse.ok(admin.bans(a),r);}
    @PostMapping("/security/ip-bans") ApiResponse<?> ban(@AuthenticationPrincipal Actor a,@RequestBody Map<String,Object> body,HttpServletRequest r){admin.ban(a,body,abuse.ip(r));return ApiResponse.ok(null,r);}
    @DeleteMapping("/security/ip-bans") ApiResponse<?> unban(@AuthenticationPrincipal Actor a,@RequestParam String ip,HttpServletRequest r){admin.unban(a,ip);return ApiResponse.ok(null,r);}
    @GetMapping("/tasks/problems") ApiResponse<?> tasks(@AuthenticationPrincipal Actor a,HttpServletRequest r){return ApiResponse.ok(admin.problems(a),r);}
}
