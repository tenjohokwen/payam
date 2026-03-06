package com.softropic.payam.security.service;



import com.softropic.payam.security.exposed.Principal;
import com.softropic.payam.security.domain.User;
import com.softropic.payam.security.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;


/**
 * Authenticate a user from the database.
 */
@Slf4j
@Service
public class LoadUserByUserNameService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    @Transactional
    public UserDetails loadUserByUsername(final String loginId) {
        log.debug("Authenticating {}", loginId);
        final String lowercaseLogin = loginId.toLowerCase();
        final Optional<User> userFromDbOpt = userRepository.findOneByLogin(lowercaseLogin);
        if(userFromDbOpt.isPresent()) {
            final User user = userFromDbOpt.get();
            return Principal.instanceFrom(user);
        }
        throw new UsernameNotFoundException("User " + lowercaseLogin + " was not found in the database");
    }
}
