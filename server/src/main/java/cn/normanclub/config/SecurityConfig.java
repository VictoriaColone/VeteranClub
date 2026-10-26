package cn.normanclub.config;

import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import tools.jackson.databind.json.JsonMapper;
import cn.normanclub.auth.AuthService;
import cn.normanclub.security.*;
import cn.normanclub.common.ApiResponse;

@Configuration
@ConditionalOnWebApplication
public class SecurityConfig {
    @Bean SecurityFilterChain chain(HttpSecurity http,AuthService auth,AbuseService abuse,ClubConfig config,JsonMapper json)throws Exception {
        return http.cors(Customizer.withDefaults())
            // Cookie sessions use per-session CSRF hashes in ApiSecurityFilter; native Bearer requests don't use cookies.
            .csrf(csrf->csrf.disable()).formLogin(form->form.disable()).httpBasic(basic->basic.disable()).logout(logout->logout.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
            .authorizeHttpRequests(a->a
                .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll()
                .requestMatchers(HttpMethod.GET,"/actuator/health","/v1/club","/v1/catalog/games","/v1/payments/channels").permitAll()
                .requestMatchers(HttpMethod.POST,"/v1/auth/sms/send","/v1/auth/sms/login","/v1/auth/staff/login","/v1/payments/notify/wechat","/v1/payments/notify/alipay").permitAll()
                .requestMatchers("/v1/admin/**").hasAnyRole("ADMIN","CUSTOMER_SERVICE","FINANCE")
                .anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->{s.setStatus(401);s.setContentType("application/json;charset=UTF-8");json.writeValue(s.getOutputStream(),new ApiResponse<>(401,"请先登录",null,String.valueOf(r.getAttribute("requestId"))));})
                .accessDeniedHandler((r,s,x)->{s.setStatus(403);s.setContentType("application/json;charset=UTF-8");json.writeValue(s.getOutputStream(),new ApiResponse<>(403,"当前账号没有此操作权限",null,String.valueOf(r.getAttribute("requestId"))));}))
            .addFilterBefore(new ApiSecurityFilter(auth,abuse,config,json),UsernamePasswordAuthenticationFilter.class).build();
    }
    @Bean CorsConfigurationSource cors(ClubConfig config){CorsConfiguration c=new CorsConfiguration();c.setAllowedOrigins(config.allowedOrigins);c.setAllowedMethods(List.of("GET","POST","PATCH","DELETE","OPTIONS"));c.setAllowedHeaders(List.of("Content-Type","Authorization","X-CSRF-Token","X-Session-Audience","X-Client-Type","X-HTTP-Method-Override","Idempotency-Key"));c.setAllowCredentials(true);c.setMaxAge(600L);UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",c);return source;}
}
