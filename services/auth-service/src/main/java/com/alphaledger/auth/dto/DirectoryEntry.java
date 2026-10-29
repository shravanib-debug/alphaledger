package com.alphaledger.auth.dto;

import com.alphaledger.auth.domain.Role;
import com.alphaledger.auth.domain.UserAccount;

/** One user in the directory: no email or other personal details. */
public record DirectoryEntry(Long id, String fullName, Role role, boolean active) {

    public static DirectoryEntry from(UserAccount user) {
        return new DirectoryEntry(user.getId(), user.getFullName(), user.getRole(), user.isActive());
    }
}
