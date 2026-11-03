package cn.normanclub.config;

import java.net.URI;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ClubConfig {
    public final boolean development, cookieSecure, jobsEnabled;
    public final byte[] encryptionKey, hashKey;
    public final List<String> allowedOrigins, trustedProxies;
    public final String smsProvider, smsSign, smsTemplate, smsOutbox;
    public final int smsDailyLimit;
    public ClubConfig(Environment env) {
        development = env.getProperty("club.development", Boolean.class, false);
        cookieSecure = env.getProperty("club.cookie-secure", Boolean.class, true);
        jobsEnabled = env.getProperty("club.jobs-enabled", Boolean.class, true);
        encryptionKey = key(env.getProperty("club.encryption-key", ""));
        hashKey = key(env.getProperty("club.hash-key", ""));
        allowedOrigins = csv(env.getProperty("club.allowed-origins", ""));
        trustedProxies = csv(env.getProperty("club.trusted-proxies", ""));
        smsProvider = env.getProperty("club.sms-provider", "disabled");
        smsSign = env.getProperty("club.sms-sign", ""); smsTemplate = env.getProperty("club.sms-template", "");
        smsOutbox = env.getProperty("club.sms-outbox", ".local/sms-outbox.jsonl");
        smsDailyLimit = env.getProperty("club.sms-daily-limit", Integer.class, 200);
        if (!development && (!cookieSecure || smsProvider.equals("outbox")))
            throw new IllegalStateException("正式环境要求 HTTPS、安全 Cookie，且禁止本地短信收件箱");
        if (allowedOrigins.isEmpty() || allowedOrigins.stream().anyMatch(s -> !origin(s))) throw new IllegalStateException("ALLOWED_ORIGINS 必须为完整且明确的来源，不得含路径、通配符或用户信息；正式环境仅限 HTTPS");
        if (!development && Arrays.equals(encryptionKey,hashKey)) throw new IllegalStateException("加密与哈希必须使用不同的随机密钥");
    }
    private boolean origin(String value){
        try{URI uri=URI.create(value);return ("https".equals(uri.getScheme())||development&&"http".equals(uri.getScheme()))&&uri.getHost()!=null&&uri.getRawUserInfo()==null&&uri.getRawQuery()==null&&uri.getRawFragment()==null&&uri.getRawPath().isEmpty()&&uri.getPort()!=0&&uri.getPort()<=65535&&!value.contains("*");}
        catch(IllegalArgumentException e){return false;}
    }
    private byte[] key(String value) {
        try { byte[] key = Base64.getDecoder().decode(value); if (key.length == 32) return key; }
        catch (IllegalArgumentException ignored) { }
        throw new IllegalStateException("CLUB_ENCRYPTION_KEY 和 CLUB_HASH_KEY 必须分别配置为随机的 32 字节 Base64 密钥");
    }
    private List<String> csv(String value) { return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(); }
}
