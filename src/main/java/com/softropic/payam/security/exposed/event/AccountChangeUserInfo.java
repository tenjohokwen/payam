package com.softropic.payam.security.exposed.event;

public record AccountChangeUserInfo(
    String email,
    String firstname,
    String lastname,
    String langKey,
    String title,
    String gender
) {}
