package cn.normanclub.admin;

import java.nio.file.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Optional, local-only provisioning. No bundled credentials or public bootstrap API. */
@Component
public class ManagerBootstrap implements ApplicationRunner {
    private final StaffService staff;private final Environment env;
    public ManagerBootstrap(StaffService staff,Environment env){this.staff=staff;this.env=env;}
    @Override public void run(ApplicationArguments args)throws Exception{
        String file=env.getProperty("club.manager-password-hash-file","");
        if(file.isBlank())return;
        Path path=Path.of(file);
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>100)
            throw new IllegalStateException("管理员哈希文件无效");
        if(Files.getPosixFilePermissions(path).stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))
            throw new IllegalStateException("管理员哈希文件必须仅文件所有者可读");
        staff.provisionManager(Files.readString(path).strip());
    }
}
