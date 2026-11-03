package cn.normanclub.payment;

import java.net.URI;
import java.nio.file.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import cn.normanclub.config.ClubConfig;

@Component
public class WechatConfig {
    public final boolean enabled;public final String merchantId,appId,serial,privateKeyPath,publicKeyPath,publicKeyId,apiKey,appSecret,origin;
    public WechatConfig(Environment env,ClubConfig club){
        enabled=env.getProperty("club.wechat.enabled",Boolean.class,false);
        merchantId=env.getProperty("club.wechat.merchant-id","");appId=env.getProperty("club.wechat.app-id","");serial=env.getProperty("club.wechat.serial","");privateKeyPath=env.getProperty("club.wechat.private-key-path","");publicKeyPath=env.getProperty("club.wechat.public-key-path","");publicKeyId=env.getProperty("club.wechat.public-key-id","");origin=env.getProperty("club.wechat.origin","");
        if(!enabled){apiKey="";appSecret="";return;}
        if(club.development||!merchantId.matches("[0-9]{6,20}")||!appId.matches("wx[A-Za-z0-9]{16}")||!serial.matches("[A-Fa-f0-9]{20,64}")||!publicKeyId.startsWith("PUB_KEY_ID_")||!club.allowedOrigins.contains(origin))throw new IllegalStateException("真实微信支付仅允许完整配置的正式HTTPS环境");
        URI site=URI.create(origin);if(!"https".equals(site.getScheme())||site.getPort()!=-1||site.getRawUserInfo()!=null||site.getHost()==null||!site.getRawPath().isEmpty())throw new IllegalStateException("微信支付正式来源须为标准HTTPS域名");
        apiKey=secret(env.getProperty("club.wechat.api-key-file",""));appSecret=secret(env.getProperty("club.wechat.app-secret-file",""));
        if(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length!=32||!appSecret.matches("[A-Za-z0-9]{16,128}")||!Path.of(privateKeyPath).isAbsolute()||!Path.of(publicKeyPath).isAbsolute()||!Files.isReadable(Path.of(privateKeyPath))||!Files.isReadable(Path.of(publicKeyPath)))throw new IllegalStateException("微信支付密钥文件配置不完整");
    }
    private String secret(String file){try{var p=Path.of(file);if(!p.isAbsolute()||Files.size(p)>1024)throw new IllegalStateException();return Files.readString(p).strip();}catch(Exception e){throw new IllegalStateException("微信支付密钥文件不可读");}}
    public String callback(){return origin+"/api/v1/payments/wechat/oauth/callback";}
    public String notifyUrl(){return origin+"/api/v1/payments/notify/wechat";}
}
