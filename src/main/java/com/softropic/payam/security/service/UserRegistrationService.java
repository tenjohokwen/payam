package com.softropic.payam.security.service;

import com.softropic.payam.common.persistence.EntityStatus;
import com.softropic.payam.common.util.RandomUtil;
import com.softropic.payam.security.common.util.SecurityConstants;
import com.softropic.payam.security.domain.Authority;
import com.softropic.payam.security.domain.User;
import com.softropic.payam.security.repository.AuthorityRepository;
import com.softropic.payam.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Service for handling user registration and activation.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UserRegistrationService {

    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates a new user with the provided information.
     * The user is initially inactive and requires activation via activation key.
     *
     * @param user the user information
     * @param password the user's password (will be encoded)
     * @return the created user
     */
    public User createUser(final User user, final String password) {
        final User newUser = new User();
        final Authority authority = authorityRepository.findOneByName(SecurityConstants.ROLE_USER).orElse(null);
        final Set<Authority> authorities = new HashSet<>();
        final String encryptedPassword = passwordEncoder.encode(password);

        newUser.setLogin(user.getLogin());
        newUser.setLoginIdType(user.getLoginIdType());
        newUser.setPassword(encryptedPassword);
        newUser.setTitle(user.getTitle());
        newUser.setFirstName(user.getFirstName());
        newUser.setLastName(user.getLastName());
        newUser.setEmail(user.getEmail());
        newUser.setLangKey(user.getLangKey());
        newUser.setDateOfBirth(user.getDateOfBirth());
        newUser.setGender(user.getGender());
        newUser.setPhone(user.getPhone());
        newUser.setNationalId(user.getNationalId());
        newUser.setOtpEnabled(user.isOtpEnabled());

        // new user is not active
        newUser.setActivated(false);
        newUser.setStatus(EntityStatus.INACTIVE);

        // new user gets registration key
        newUser.setActivationKey(RandomUtil.generateActivationKey());

        authorities.add(authority);
        newUser.setAuthorities(authorities);

        userRepository.save(newUser);
        log.debug("Created Information for User: {}", newUser);
        return newUser;
    }

    /**
     * Activates a user account using the provided activation key.
     * This method can be called by an anonymous user.
     * Delegates to User domain entity for activation logic.
     *
     * @param key the activation key
     * @return the activated user if found, empty otherwise
     */
    public Optional<User> activateUser(final String key) {
        log.debug("Activating user for activation key {}", key);
        return userRepository.findInactivatedByActivationKey(key)
                .map(user -> {
                    user.activate(); // Use domain method
                    user.setStatus(EntityStatus.ACTIVE);
                    log.debug("Activated user: {}", user);
                    return user;
                });
    }

    /**
     * Checks if a user exists with the given email or login.
     * Useful for validation during registration.
     *
     * @param email the email to check
     * @param login the login to check
     * @return the user if found, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<User> findUserByEmailOrLogin(final String email, final String login) {
        return userRepository.findOneByEmailOrLogin(email, login);
    }
}
