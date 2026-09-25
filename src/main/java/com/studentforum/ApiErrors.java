package com.studentforum;

import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class) org.springframework.http.ResponseEntity<Map<String,String>> expected(ResponseStatusException error) { return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()==null?error.getStatusCode().toString():error.getReason())); }
    @ExceptionHandler(DuplicateKeyException.class) org.springframework.http.ResponseEntity<Map<String,String>> duplicate() { return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message","名称或邮箱已存在")); }
}
