package com.studentforum.model;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ContentRules {
    private ContentRules() {}

    public static void text(String value,int min,int max) {
        if (value == null || value.trim().length() < min || value.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"内容长度不符合要求");
        if (value.toLowerCase().matches("(?s).*(赌博|色情广告|代写论文).*") ) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"内容包含不允许的词语");
    }

    public static String image(Object value) {
        if (value == null || "".equals(value)) return null;
        String path = String.valueOf(value);
        if (!path.matches("/uploads/[0-9a-fA-F-]{36}\\.(jpg|png|webp)")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"图片路径无效");
        return path;
    }

    public static String attachment(Object value) {
        if (value==null || "".equals(value)) return null;
        String path=String.valueOf(value);
        if (!path.matches("/uploads/[0-9a-fA-F-]{36}\\.(pdf|txt|zip)")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"附件路径无效");
        return path;
    }
}
