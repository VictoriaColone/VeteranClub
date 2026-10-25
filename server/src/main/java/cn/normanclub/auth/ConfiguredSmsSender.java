package cn.normanclub.auth;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import org.springframework.stereotype.Component;
import cn.normanclub.common.ApiException;
import cn.normanclub.config.ClubConfig;

@Component
public class ConfiguredSmsSender implements SmsSender {
    private final ClubConfig config;private final Clock clock;
    public ConfiguredSmsSender(ClubConfig config,Clock clock) {this.config=config;this.clock=clock;}
    @Override public synchronized void send(String phone,String code) {
        if(config.development && config.smsProvider.equals("outbox")) {
            try {
                Path file=Path.of(config.smsOutbox).toAbsolutePath();Files.createDirectories(file.getParent());
                if(!Files.exists(file))Files.createFile(file,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
                Files.writeString(file,"{\"at\":"+clock.millis()+",\"phone\":\""+phone+"\",\"code\":\""+code+"\",\"developmentOnly\":true}\n",StandardOpenOption.APPEND);
                return;
            } catch(Exception ex) {throw new ApiException(503,"本地短信收件箱不可用");}
        }
        if(!config.smsProvider.equals("aliyun") || config.smsSign.isBlank() || config.smsTemplate.isBlank())
            throw new ApiException(503,"短信服务尚未配置，请联系管理员");
        try {
            // Official SDK credential chain: RAM instance role or server environment. Never frontend credentials.
            var credentials=new com.aliyun.credentials.Client();
            var options=new com.aliyun.teaopenapi.models.Config().setCredential(credentials).setEndpoint("dysmsapi.aliyuncs.com").setProtocol("https").setConnectTimeout(3000).setReadTimeout(5000);
            var client=new com.aliyun.dysmsapi20170525.Client(options);
            var request=new com.aliyun.dysmsapi20170525.models.SendSmsRequest().setPhoneNumbers(phone).setSignName(config.smsSign).setTemplateCode(config.smsTemplate).setTemplateParam("{\"code\":\""+code+"\"}");
            var response=client.sendSms(request);
            if(response.getBody()==null || !"OK".equals(response.getBody().getCode()))throw new IllegalStateException();
        } catch(Exception ex) {throw new ApiException(503,"验证码发送失败，请稍后再试");}
    }
}
