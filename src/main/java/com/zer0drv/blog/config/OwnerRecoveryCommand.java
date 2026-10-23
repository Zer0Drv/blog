package com.zer0drv.blog.config;

import com.zer0drv.blog.admin.service.OwnerRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="blog.owner.recovery-command", havingValue="true")
public class OwnerRecoveryCommand implements ApplicationRunner {
    private final OwnerRecoveryService recovery;
    private final ConfigurableApplicationContext context;
    public void run(ApplicationArguments arguments) {
        if (!"none".equalsIgnoreCase(context.getEnvironment().getProperty("spring.main.web-application-type")))
            throw new IllegalStateException("恢复命令必须以非 Web 模式运行");
        var console = System.console();
        if (console == null) throw new IllegalStateException("恢复命令要求交互终端，不接受环境变量密码或默认密码");
        String username = console.readLine("目标用户名: ");
        String mode = console.readLine("输入 INITIALIZE 初始化首个所有者，或 RECOVER 恢复已有所有者: ");
        if (!java.util.Set.of("INITIALIZE", "RECOVER").contains(mode)) throw new IllegalArgumentException("无效恢复模式");
        char[] password = console.readPassword("新的恢复密码（6–72 字符，不回显）: ");
        try {
            recovery.recover(username, new String(password), "INITIALIZE".equals(mode));
            console.printf("所有者访问已恢复，历史会话与二次验证已撤销。请登录后重新绑定二次验证。%n");
        } finally { java.util.Arrays.fill(password, '\0'); context.close(); }
    }
}
