package cn.normanclub.config;

import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class CoreConfig {
    @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder(12);}
}
