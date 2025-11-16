package org.skypulse.auth.dto;


public class RegisterRequest {
    public String first_name;
    public String last_name;
    public String email;
    public String password;
    public String company_name;
    public Integer role_id; // can be null -> default Viewer
}

