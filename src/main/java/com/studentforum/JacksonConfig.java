package com.studentforum;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * 统一 JDBC 取出来的时间字段的 JSON 形态。
 *
 * <p>接口的数据几乎全部来自 JdbcTemplate，返回的是 {@link java.sql.Date} 与 {@link java.sql.Timestamp}。
 * Jackson 默认会把它们写成时间戳数字，前端拿到的就不是日期字符串：`String(date).slice(0, 10)` 这类
 * 处理会拿到一串数字，日历、到期判断、日期输入框（value="...")全都会错位。
 * 这里只对这两个 JDBC 类型生效，既有的接口因此也一并变成稳定的日期字符串。
 */
@Configuration
class JacksonConfig {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    Jackson2ObjectMapperBuilderCustomizer jdbcDateCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("jdbc-time");
            module.addSerializer(java.sql.Date.class, new SqlDateSerializer());
            module.addSerializer(Timestamp.class, new TimestampSerializer());
            builder.modulesToInstall(module);
        };
    }

    /** DATE 只保留年月日，日历、日期输入框都按这个格式取值。 */
    private static class SqlDateSerializer extends StdSerializer<java.sql.Date> {
        SqlDateSerializer() {
            super(java.sql.Date.class);
        }

        @Override
        public void serialize(java.sql.Date value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString(value.toLocalDate().toString());
        }
    }

    /** DATETIME 统一成 "yyyy-MM-dd HH:mm:ss"，与前端 date() 的解析保持一致。 */
    private static class TimestampSerializer extends StdSerializer<Timestamp> {
        TimestampSerializer() {
            super(Timestamp.class);
        }

        @Override
        public void serialize(Timestamp value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString(value.toLocalDateTime().format(DATETIME));
        }
    }
}
