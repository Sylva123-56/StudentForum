package com.studentforum.dto;

/** Request fields for moderating a post without changing the JSON contract. */
public record AdminPostStatusRequest(String status) { }
