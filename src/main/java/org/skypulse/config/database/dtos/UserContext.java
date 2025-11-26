package org.skypulse.config.database.dtos;

import io.undertow.util.AttachmentKey;

import java.util.UUID;

/**
 * Stores authenticated user info for the current request.
 */
public record UserContext(Long userId, UUID uuid, String firstName, String lastName, String email, Integer roleId,
                          String roleName, String companyName) {

    public static final AttachmentKey<UserContext> ATTACHMENT_KEY = AttachmentKey.create(UserContext.class);

}
