package cn.normanclub;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class NormanApplication {
    public static void main(String[] args) {
        var context=SpringApplication.run(NormanApplication.class,args);
        if(context.getEnvironment().containsProperty("club.command"))System.exit(SpringApplication.exit(context));
    }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
