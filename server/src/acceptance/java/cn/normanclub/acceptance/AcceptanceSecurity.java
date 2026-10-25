package cn.normanclub.acceptance;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AcceptanceSecurity {
    /** Only this exact bootstrap route is public; normal API authentication remains unchanged. */
    @Bean @Order(0) SecurityFilterChain acceptanceChain(HttpSecurity http,AcceptanceGuard guard,cn.normanclub.auth.AuthService auth,cn.normanclub.security.AbuseService abuse,cn.normanclub.config.ClubConfig config,tools.jackson.databind.json.JsonMapper json)throws Exception {
        return http.securityMatcher("/v1/auth/sms/login/acceptance").csrf(c->c.disable()).formLogin(f->f.disable()).httpBasic(b->b.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.POST,"/v1/auth/sms/login/acceptance").permitAll().anyRequest().denyAll())
            .headers(h->h.cacheControl(c->{}))
            .addFilterBefore(new cn.normanclub.security.ApiSecurityFilter(auth,abuse,config,json),org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class).build();
    }
}
