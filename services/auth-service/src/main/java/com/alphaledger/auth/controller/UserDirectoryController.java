package com.alphaledger.auth.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alphaledger.auth.dto.DirectoryEntry;
import com.alphaledger.auth.service.UserService;

/**
 * A read-only list of who is who, for any signed-in user: lets the dashboard show names instead of user IDs (break
 * assignees, audit trails) and lets Ops pick an assignee. It deliberately exposes only ID, name, role and whether the
 * account is active; emails, status details and user management stay in the Admin-only {@link UserController}.
 * Disabled users are listed (inactive) because past actions still name them.
 */
@RestController
public class UserDirectoryController {

    private final UserService userService;

    public UserDirectoryController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/api/v1/auth/users/directory")
    public List<DirectoryEntry> directory() {
        return userService.directory();
    }
}
