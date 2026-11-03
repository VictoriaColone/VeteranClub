package cn.normanclub.auth;

import java.text.Normalizer;
import java.time.*;
import java.time.format.*;
import java.util.Locale;
import cn.normanclub.common.ApiException;
import static cn.normanclub.common.ApiException.require;

/** Structural validation only. It does not query an authoritative identity source. */
public final class ResidentIdentity {
    private ResidentIdentity() { }
    private static final int[] WEIGHTS={7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};
    private static final String CHECKSUM="10X98765432";
    public static LocalDate today(Clock clock){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));}
    public static String name(String raw){
        require(raw!=null,400,"请填写真实姓名");String value=Normalizer.normalize(raw.trim(),Normalizer.Form.NFC);
        require(value.length()>=2&&value.length()<=60&&value.matches("[\\p{L}·•]+(?: [\\p{L}·•]+)*"),400,"请填写有效的真实姓名");return value;
    }
    public static String number(String raw,Clock clock){
        require(raw!=null,400,"请填写18位居民身份证号码");String value=raw.trim().toUpperCase(Locale.ROOT);
        require(value.matches("[1-9][0-9]{16}[0-9X]")&&!value.substring(14,17).equals("000"),400,"身份证号码格式不正确");
        int sum=0;for(int i=0;i<17;i++)sum+=(value.charAt(i)-'0')*WEIGHTS[i];
        require(CHECKSUM.charAt(sum%11)==value.charAt(17),400,"身份证号码校验失败，请检查填写内容");
        LocalDate birth=birth(value),today=today(clock);require(birth.getYear()>=1900&&!birth.isAfter(today),400,"身份证出生日期不正确");return value;
    }
    public static LocalDate birth(String number){
        try{return LocalDate.parse(number.substring(6,14),DateTimeFormatter.BASIC_ISO_DATE.withResolverStyle(ResolverStyle.STRICT));}
        catch(DateTimeException|IndexOutOfBoundsException e){throw new ApiException(400,"身份证出生日期不正确");}
    }
    public static boolean adult(String number,Clock clock){return !birth(number).plusYears(18).isAfter(today(clock));}
    public static String maskedName(String name){return name.substring(0,Character.charCount(name.codePointAt(0)))+"**";}
    public static String maskedNumber(String number){return "**************"+number.substring(14);}
}
