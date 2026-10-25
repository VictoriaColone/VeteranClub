package cn.normanclub.acceptance;

import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import cn.normanclub.config.ClubConfig;
import static cn.normanclub.common.ApiException.require;

/** This class is only compiled by -Pacceptance; the normal release cannot enable it. */
@Component
public class AcceptanceGuard {
    private final ClubConfig config;
    public AcceptanceGuard(ClubConfig config,Environment env){
        this.config=config;
        validate(config.development,env.getProperty("ACCEPTANCE_MODE","false"),
            env.getProperty("server.address",""),env.getProperty("spring.datasource.url",""),config.allowedOrigins,
            env.getProperty("ACCEPTANCE_RDS_HOST",""));
        if(!config.smsProvider.equals("disabled")||!config.trustedProxies.stream().allMatch(p->p.equals("127.0.0.1")))
            throw new IllegalStateException("验收包禁止真实短信及非本机代理");
    }
    public static void validate(boolean development,String enabled,String address,String database,java.util.List<String> origins){
        validate(development,enabled,address,database,origins,"");
    }
    public static void validate(boolean development,String enabled,String address,String database,java.util.List<String> origins,String rdsHost){
        boolean local=database.matches("jdbc:(mysql://127\\.0\\.0\\.1:[0-9]+/norman_acceptance(?:\\?.*)?|h2:mem:norman_acceptance.*)");
        if(!development||!enabled.equals("true")||!address.equals("127.0.0.1")||
            !(local||approvedRds(database,rdsHost))||
            origins.isEmpty()||!origins.stream().allMatch(s->s.matches("http://(127\\.0\\.0\\.1|localhost):[0-9]+")))
            throw new IllegalStateException("验收包只允许本机监听与来源、独立 norman_acceptance 库和显式核准的加密数据库连接");
    }
    private static boolean approvedRds(String database,String expectedHost){
        if(!expectedHost.matches("rm-[a-z0-9-]+\\.mysql\\.rds\\.aliyuncs\\.com")||!database.startsWith("jdbc:mysql://"))return false;
        try{
            var uri=java.net.URI.create(database.substring(5));
            if(!expectedHost.equals(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=3306||
                !"/norman_acceptance".equals(uri.getPath())||uri.getFragment()!=null)return false;
            var query=new java.util.TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);
            var allowed=Set.of("sslmode","useunicode","characterencoding","servertimezone","connecttimeout","sockettimeout",
                "trustcertificatekeystoreurl","trustcertificatekeystorepassword","trustcertificatekeystoretype");
            for(String pair:uri.getRawQuery().split("&")){
                String[] parts=pair.split("=",2);
                if(parts.length!=2)return false;
                String key=java.net.URLDecoder.decode(parts[0],java.nio.charset.StandardCharsets.UTF_8);
                String value=java.net.URLDecoder.decode(parts[1],java.nio.charset.StandardCharsets.UTF_8);
                if(!allowed.contains(key.toLowerCase(java.util.Locale.ROOT))||query.putIfAbsent(key,value)!=null)return false;
            }
            if(!"VERIFY_IDENTITY".equals(query.get("sslMode")))return false;
            if(query.containsKey("trustCertificateKeyStoreUrl")&&
                !"file:/etc/norman-rds-ca/rds-ca.jks".equals(query.get("trustCertificateKeyStoreUrl")))return false;
            return true;
        }catch(IllegalArgumentException|NullPointerException invalid){return false;}
    }
    public void check(HttpServletRequest request){
        require(Set.of("127.0.0.1","0:0:0:0:0:0:0:1","::1").contains(request.getRemoteAddr()),403,"验收入口仅允许服务器本机连接");
        require("h5".equals(request.getHeader("X-Client-Type")),403,"验收入口仅供 H5 内部测试");
        require(config.allowedOrigins.contains(request.getHeader("Origin")),403,"验收请求来源不受信任");
    }
}
