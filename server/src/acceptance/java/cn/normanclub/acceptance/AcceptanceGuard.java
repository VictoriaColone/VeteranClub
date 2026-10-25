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
            env.getProperty("server.address",""),env.getProperty("spring.datasource.url",""),config.allowedOrigins);
        if(!config.smsProvider.equals("disabled")||!config.trustedProxies.stream().allMatch(p->p.equals("127.0.0.1")))
            throw new IllegalStateException("验收包禁止真实短信及非本机代理");
    }
    public static void validate(boolean development,String enabled,String address,String database,java.util.List<String> origins){
        if(!development||!enabled.equals("true")||!address.equals("127.0.0.1")||
            !database.matches("jdbc:(mysql://127\\.0\\.0\\.1:[0-9]+/norman_acceptance(?:\\?.*)?|h2:mem:norman_acceptance.*)")||
            origins.isEmpty()||!origins.stream().allMatch(s->s.matches("http://(127\\.0\\.0\\.1|localhost):[0-9]+")))
            throw new IllegalStateException("验收包只允许显式启用、本机监听、本机来源和独立 norman_acceptance 数据库");
    }
    public void check(HttpServletRequest request){
        require(Set.of("127.0.0.1","0:0:0:0:0:0:0:1","::1").contains(request.getRemoteAddr()),403,"验收入口仅允许服务器本机连接");
        require("h5".equals(request.getHeader("X-Client-Type")),403,"验收入口仅供 H5 内部测试");
        require(config.allowedOrigins.contains(request.getHeader("Origin")),403,"验收请求来源不受信任");
    }
}
