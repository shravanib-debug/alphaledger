package com.alphaledger.auth.service;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.auth.domain.UserAccount;
import com.alphaledger.auth.dto.CreateUserRequest;
import com.alphaledger.auth.dto.DirectoryEntry;
import com.alphaledger.auth.dto.UserResponse;
import com.alphaledger.auth.repository.UserAccountRepository;
import com.alphaledger.common.error.ConflictException;
import com.alphaledger.common.error.NotFoundException;

@Service
public class UserService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(UserAccountRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String email = AuthService.normalize(request.email());
        if (users.existsByEmail(email)) {
            throw new ConflictException("A user with email " + email + " already exists");
        }
        UserAccount user = new UserAccount(email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), request.role(), clock.instant());
        return UserResponse.from(users.save(user));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAll(Sort.by("id")).stream().map(UserResponse::from).toList();
    }

    /** Every user, active first then by name, with only the fields the directory shows. */
    @Transactional(readOnly = true)
    public List<DirectoryEntry> directory() {
        return users.findAll(Sort.by("fullName", "id")).stream()
                .map(DirectoryEntry::from)
                .sorted(Comparator.comparing(DirectoryEntry::active).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(long id) {
        return users.findById(id).map(UserResponse::from)
                .orElseThrow(() -> new NotFoundException("User " + id + " not found"));
    }
}
