package cn.normanclub.admin;

import java.nio.file.*;
import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Offline provisioning only. No default accounts/passwords or public bootstrap endpoint. */
@Component
@ConditionalOnProperty(name="club.command")
public class StaffCommand implements ApplicationRunner {
    private final StaffService staff;private final Environment env;
    public StaffCommand(StaffService staff,Environment env){this.staff=staff;this.env=env;}
    @Override public void run(ApplicationArguments args)throws Exception{
        String username=env.getRequiredProperty("club.username"),name=env.getProperty("club.staff-name","平台管理员"),command=env.getRequiredProperty("club.command");
        char[] password;
        String file=env.getProperty("CLUB_PASSWORD_FILE");
        if(file!=null){Path path=Path.of(file);var permissions=Files.getPosixFilePermissions(path);if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))throw new IllegalStateException("密码文件权限必须为 600");password=Files.readString(path).strip().toCharArray();}
        else{if(System.console()==null)throw new IllegalStateException("请使用交互终端输入密码，或通过权限 600 的 CLUB_PASSWORD_FILE 提供");password=System.console().readPassword("设置员工密码（至少8位，包含字母和数字）: ");}
        try{if(command.equals("bootstrap-admin"))staff.bootstrap(username,name,new String(password));else if(command.equals("reset-staff"))staff.resetLocally(username,new String(password));else throw new IllegalArgumentException("不支持的维护命令");}
        finally{Arrays.fill(password,'\0');}
        System.out.println("员工认证配置完成。使用账号密码登录；未输出密码。");
    }
}
