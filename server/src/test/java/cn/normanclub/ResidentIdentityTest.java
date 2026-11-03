package cn.normanclub;

import java.time.*;
import org.junit.jupiter.api.Test;
import cn.normanclub.auth.ResidentIdentity;
import cn.normanclub.common.ApiException;
import static org.junit.jupiter.api.Assertions.*;

class ResidentIdentityTest {
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-30T16:00:00Z"),ZoneOffset.UTC);
    // Synthetic, unissued test identifiers. This deliberately is not a government identity fixture.
    static String number(String birth,int sequence){
        String prefix=String.format(java.util.Locale.ROOT,"%06d%s%03d",900000+(sequence/999)%99999,birth,sequence%999+1);
        int[] weights={7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};int sum=0;
        for(int i=0;i<17;i++)sum+=(prefix.charAt(i)-'0')*weights[i];return prefix+"10X98765432".charAt(sum%11);
    }
    @Test void adulthoodUsesChinaMidnightAndExactEighteenthBirthday(){
        assertTrue(ResidentIdentity.adult(number("20081031",1),CLOCK));
        assertFalse(ResidentIdentity.adult(number("20081101",2),CLOCK));
        assertFalse(ResidentIdentity.adult(number("20081031",1),Clock.offset(CLOCK,Duration.ofMillis(-1))));
        assertTrue(ResidentIdentity.adult(number("20000229",1),CLOCK));
    }
    @Test void structuralChecksRejectBadChecksumImpossibleDatesAndFutureBirths(){
        String good=number("19900101",5);assertEquals(good,ResidentIdentity.number(" "+good.toLowerCase()+" ",CLOCK));
        for(String value:new String[]{good.substring(0,17)+(good.endsWith("0")?"1":"0"),number("20010229",1),number("20300101",1),"123",good.substring(0,17)+"!"})assertThrows(ApiException.class,()->ResidentIdentity.number(value,CLOCK));
        assertThrows(ApiException.class,()->ResidentIdentity.name("1234"));assertEquals("测试·用户",ResidentIdentity.name(" 测试·用户 "));
    }
}
