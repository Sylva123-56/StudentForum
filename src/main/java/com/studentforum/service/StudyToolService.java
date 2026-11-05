package com.studentforum.service;

import com.studentforum.model.StudyStreak;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 学习工具共用的输入、日期与连续天数工具。
 */
@Service
public class StudyToolService {
    /** 打卡有意义的时长上下限：0 表示只记"我来打过卡"，不校准时长。 */
    public static final int MAX_MINUTES = 720;

    // ---------- 输入工具 ----------

    public long number(Map<String, Object> input, String key, long fallback) {
        Object value = input == null ? null : input.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && text.trim().matches("-?\\d+")) return Long.parseLong(text.trim());
        return fallback;
    }

    public String text(Map<String, Object> input, String key, int min, int max, String message) {
        Object value = input == null ? null : input.get(key);
        String result = value == null ? "" : String.valueOf(value).trim();
        if (result.length() < min || result.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        if (result.contains("赌博") || result.contains("色情广告") || result.contains("代写论文")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容包含不允许的词语");
        return result;
    }

    /** 可空的短文本：只做长度上限校验，空串一律归一成 null，避免库里出现 '' 和 NULL 两种空。 */
    public String optionalText(Map<String, Object> input, String key, int max) {
        Object value = input == null ? null : input.get(key);
        String result = value == null ? "" : String.valueOf(value).trim();
        if (result.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容过长");
        return result.isEmpty() ? null : result;
    }

    /** 复用 ContentRules.image 的校验（必须是我们自己上传出来的 /uploads/<uuid>.<ext>）。 */
    public String image(Object value) {
        return com.studentforum.model.ContentRules.image(value);
    }

    public LocalDate date(Map<String, Object> input, String key, LocalDate fallback) {
        Object value = input == null ? null : input.get(key);
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式无效");
        }
    }

    public long rowNumber(Map<String, Object> row, String key) {
        // 可选查询查不到行时统一当 0 处理
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }

    public boolean rowFlag(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value instanceof Boolean flag) return flag;
        return value instanceof Number number && number.intValue() != 0;
    }

    // ---------- 连续打卡 ----------

    public int streak(List<LocalDate> dates, LocalDate today) {
        return new StudyStreak(dates, today).length();
    }

    public static LocalDate toDate(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof java.time.LocalDate date) return date;
        if (value instanceof java.sql.Timestamp stamp) return stamp.toLocalDateTime().toLocalDate();
        if (value instanceof java.util.Date date) return new java.sql.Date(date.getTime()).toLocalDate();
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    public static final Set<String> VISIBILITIES = Set.of("public", "private", "group");
}
