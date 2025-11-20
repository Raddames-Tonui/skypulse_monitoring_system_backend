package org.skypulse.config.security;

import io.undertow.util.AttachmentKey;

import java.util.UUID;

/**
 * Stores authenticated user info for the current request.
 */
public class UserContext {

    public static final AttachmentKey<UserContext> ATTACHMENT_KEY = AttachmentKey.create(UserContext.class);

    private final Long userId;
    private final UUID uuid;
    private final String firstName;
    private final String lastName;
    private final String email;
    private final Integer roleId;

    public UserContext(Long userId, UUID uuid, String firstName, String lastName, String email, Integer roleId) {
        this.userId = userId;
        this.uuid = uuid;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.roleId = roleId;
    }

    public Long getUserId() {return userId;}
    public UUID getUuid() {return uuid;}
    public String getFirstName() {return firstName;}
    public String getLastName() {return lastName;}
    public String getEmail() {return email;}
    public Integer getRoleId() {return roleId;}
}
