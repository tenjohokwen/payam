package com.softropic.payam.security.api;




import com.softropic.payam.common.message.Failure;
import com.softropic.payam.common.message.Response;
import com.softropic.payam.common.message.Success;
import com.softropic.payam.security.exposed.ChangePasswordDto;
import com.softropic.payam.security.exposed.UserDto;
import com.softropic.payam.security.exposed.exception.AuthorizationException;
import com.softropic.payam.security.exposed.exception.SecurityError;
import com.softropic.payam.security.service.UserService;
import com.softropic.payam.security.service.UserRegistrationService;
import com.softropic.payam.security.core.mapper.UserMapper;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;


import io.micrometer.core.annotation.Timed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * REST controller for managing the current user's account.
 */
@RestController
@RequestMapping("/v1/account")
public class AccountResource {

    private final Logger log = LoggerFactory.getLogger(AccountResource.class);

    @Autowired
    private UserService userService;

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AccountManagementFacade accountManagementFacade;

    /**
     * POST  /register to register the user.
     * @param userDTO holds the user's data
     * @return ResponseEntity
     */
    @PostMapping(value="/register", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success registerAccount(@Valid @RequestBody final UserDto userDTO)  {
        final String emailSendId = accountManagementFacade.registerAccount(userDTO);
        final String msg = "You will receive an email shortly with an activation key or else contact support with the help code";
        return new Success(emailSendId, "user.creation.feedback", msg, Map.of());
    }

    /**
     * POST  /regislink to register the user.
     * Endpoint to resend the registration link
     *
     * @return ResponseEntity
     */
    @PostMapping(value="/regislink", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success registerLinkResend(@NotNull String login, @NotNull String password)  {
        final String emailSendId = accountManagementFacade.resendRegistrationLink(login, password);
        final String msg = "You will receive an email shortly with an activation key or else contact support with the help code";
        return new Success(emailSendId, "user.creation.feedback", msg, Map.of());
    }

    /**
     * GET  /activate to activate the registered user.
     * @param key is the string used for activation
     * @return response status
     */
    @PostMapping(value = "/activate",  produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success activateAccount(@RequestParam("key")final  String key) {
        return userRegistrationService.activateUser(key)
                          .map(u -> new Success(null,
                                                "user.activation.success",
                                                "Account has been activated",
                                                Map.of()))
                          .orElseThrow(() -> new AuthorizationException("Activation key invalid or already used",
                                                                        SecurityError.INVALID_ACTIVATION_KEY));
    }

    /**
     * GET  /authenticate to check if the user is authenticated, and return its login.
     * @param request httpServletRequest
     * @return remote user
     */
    @GetMapping(value = "/authenticate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public String getAuthenticatedUser(final HttpServletRequest request) {
        log.debug("REST request to check if the current user is authenticated");
        return request.getRemoteUser();
    }

    /**
     * GET  / to get the current user.
     * @return Http status code
     */
    @GetMapping(value = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public ResponseEntity<UserDto> getAccount() {
        return Optional.ofNullable(userService.getUserWithAuthorities())
            .map(user -> new ResponseEntity<>(userMapper.toUserDto(user), HttpStatus.OK))
                .orElse(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
    }


    @PostMapping(value = "/reset_password/init", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public ResponseEntity<Response> requestPasswordReset(@RequestBody @Valid ChangePasswordDto changePasswordDto) {
        final String code = accountManagementFacade.sendPasswordResetMail(changePasswordDto);
        return new ResponseEntity<>(new Success(code,
                                                "password.reset.emailed",
                                                "Check your email for a link to reset your password",
                                                Map.of()), HttpStatus.ACCEPTED);
    }

    @PostMapping(value = "/reset_password/finish", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success finishPasswordReset(@RequestBody @Valid KeyAndPasswordDto keyAndPassword) {//password.reset.success
        accountManagementFacade.finishPasswordReset(keyAndPassword);
        return new Success(null,
                           "password.reset.success",
                           "Your password has now been reset. You can now login with your new password.",
                           Map.of());
    }

    @GetMapping(value = "/ping", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Map<String, String> pong() {
        return Map.of("server", "up");
    }

}
