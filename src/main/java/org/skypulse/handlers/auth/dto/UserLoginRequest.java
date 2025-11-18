package org.skypulse.handlers.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UserLoginRequest {
    public String userEmail;
    public String password;
    public String deviceName;


    public UserLoginRequest() {}
}
