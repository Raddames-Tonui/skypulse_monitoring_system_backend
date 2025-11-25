package org.skypulse.rest.auth;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRoles {
    String[] value();
}
