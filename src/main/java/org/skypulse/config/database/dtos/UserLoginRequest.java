package org.skypulse.config.database.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UserLoginRequest {
    public String email;
    public String password;
    public String deviceName;


    public UserLoginRequest() {}
}
