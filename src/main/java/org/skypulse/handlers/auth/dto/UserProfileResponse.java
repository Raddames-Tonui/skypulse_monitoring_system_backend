package org.skypulse.handlers.auth.dto;

import java.util.Map;

public class UserProfileResponse {
    public String uuid;
    public String firstName;
    public String lastName;
    public String email;
    public String role;

    public Map<String, Object> preferences;
    public Map<String, Map <String, Boolean>> finalPermissions;

    @Override
    public String toString() {
        return "UserProfileResponse{" +
                "uuid= '" + uuid + '\'' +
                ", role=' " + role + '\'' +
                '}';
    }
}
