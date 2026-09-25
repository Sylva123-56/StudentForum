package com.studentforum;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DemoAccounts {
    @Bean ApplicationRunner demoAccounts(ForumMapper mapper,PasswordEncoder encoder,@Value("${forum.demo-password:}") String password) {
        return args -> {
            if (password.isBlank()) return;
            if (password.length()<8) throw new IllegalArgumentException("DEMO_PASSWORD must be at least 8 characters");
            seed(mapper,encoder,"student@example.test","演示学生","student",null,password);
            seed(mapper,encoder,"helper@example.test","互助学友","student",null,password);
            seed(mapper,encoder,"moderator@example.test","板块版主","moderator",1L,password);
            seed(mapper,encoder,"admin@example.test","社区管理员","admin",null,password);
        };
    }
    private void seed(ForumMapper mapper,PasswordEncoder encoder,String email,String name,String role,Long boardId,String password) {
        if (mapper.emailExists(email)==0) mapper.demoUser(email,name,encoder.encode(password),role,boardId);
    }
}
