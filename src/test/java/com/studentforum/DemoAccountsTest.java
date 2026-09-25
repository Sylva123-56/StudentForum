package com.studentforum;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

class DemoAccountsTest {
    @Test void registersSeederWithoutBeanNameConflict() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ForumMapper.class, () -> mock(ForumMapper.class));
            context.registerBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));
            context.register(DemoAccounts.class);
            context.refresh();
            assertNotNull(context.getBean(ApplicationRunner.class));
        }
    }
}
