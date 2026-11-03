package cn.normanclub;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import cn.normanclub.config.ClubConfig;
import static org.junit.jupiter.api.Assertions.*;

class ClubConfigTest {
    MockEnvironment environment(){return new MockEnvironment()
        .withProperty("club.encryption-key","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .withProperty("club.hash-key","AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=")
        .withProperty("club.allowed-origins","https://app.example.com");}
    @Test void productionRequiresExactHttpsOriginsSecureCookiesAndIndependentKeys(){
        assertDoesNotThrow(()->new ClubConfig(environment()));
        for(String origin:new String[]{"*","https://*.example.com","https://app.example.com/","https://app.example.com/path","https://user@app.example.com","https://app.example.com?x=1","https://app.example.com#fragment","https://app.example.com:0","https://app.example.com:65536","http://app.example.com","null",""})
            assertThrows(IllegalStateException.class,()->new ClubConfig(environment().withProperty("club.allowed-origins",origin)),origin);
        assertThrows(IllegalStateException.class,()->new ClubConfig(environment().withProperty("club.cookie-secure","false")));
        assertThrows(IllegalStateException.class,()->new ClubConfig(environment().withProperty("club.sms-provider","outbox")));
        assertThrows(IllegalStateException.class,()->new ClubConfig(environment().withProperty("club.hash-key","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")));
    }
    @Test void privateDevelopmentMayUseLoopbackHttpWithExactOrigins(){
        assertDoesNotThrow(()->new ClubConfig(environment().withProperty("club.development","true").withProperty("club.cookie-secure","false").withProperty("club.allowed-origins","http://127.0.0.1:4190,http://localhost:4190")));
    }
}
