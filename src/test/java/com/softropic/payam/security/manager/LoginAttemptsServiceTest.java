package com.softropic.payam.security.manager;

import com.google.common.base.Ticker;
import com.google.common.testing.FakeTicker;

import com.softropic.payam.security.exposed.util.TestRequestMetadataProvider;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptsServiceTest {

    private LoginAttemptsService loginAttemptsService;
    private ClientIdAccessDecisionManager defaultDecisionVoter;

    // Default values for TestRequestMetadataProvider state
    private static final String DEFAULT_TEST_USERNAME = "defaultUser@example.com";
    private static final String DEFAULT_TEST_CLIENT_ID = "defaultClient";
    private static final String DEFAULT_TEST_IP_ADDRESS = "127.0.0.1";

    // Thresholds from LoginAttemptsService (consider making them package-private or test constants if possible)
    private static final int MAX_FAILED_CLIENT_ATTEMPTS = 3;
    private static final int MAX_FAILED_IP_ATTEMPTS =  MAX_FAILED_CLIENT_ATTEMPTS; //MAX_FAILED_CLIENT_ATTEMPTS * 2;
    private static final int MAX_FAILED_USER_ATTEMPTS = MAX_FAILED_CLIENT_ATTEMPTS + 2;


    @BeforeEach
    void setUp() {
        defaultDecisionVoter = new ClientIdAccessDecisionManager(List.of());
        // Default service uses system ticker. Tests needing FakeTicker will create their own instance.
        loginAttemptsService = new LoginAttemptsService(defaultDecisionVoter, Ticker.systemTicker());
        resetTestRequestMetadataProvider();
    }

    @AfterEach
    void tearDown() {
        resetTestRequestMetadataProvider();
    }

    private void resetTestRequestMetadataProvider() {
        TestRequestMetadataProvider.setUserName(DEFAULT_TEST_USERNAME);
        TestRequestMetadataProvider.setBrowserCookie(DEFAULT_TEST_CLIENT_ID);
        TestRequestMetadataProvider.setIpAddress(DEFAULT_TEST_IP_ADDRESS);
    }

    private void simulateFailedLogins(int count) {
        for (int i = 0; i < count; i++) {
            loginAttemptsService.loginFailed(TestRequestMetadataProvider.getClientInfo());
        }
    }

    private void simulateFailedLogins(LoginAttemptsService service, int count) {
        for (int i = 0; i < count; i++) {
            service.loginFailed(TestRequestMetadataProvider.getClientInfo());
        }
    }

    private void assertLoginAllowed(boolean expected, String scenario) {
        assertThat(loginAttemptsService.isAllowed(TestRequestMetadataProvider.getClientInfo()))
                .as(scenario)
                .isEqualTo(expected);
    }

    private void assertLoginAllowed(LoginAttemptsService service, boolean expected, String scenario) {
        assertThat(service.isAllowed(TestRequestMetadataProvider.getClientInfo()))
                .as(scenario)
                .isEqualTo(expected);
    }

    /**
     * The honest user can have a max of MAX_FAILED_CLIENT_ATTEMPTS failed attempts before being blocked.
     */
    @Test
    void givenClientUser_whenMaxAttemptsExceeded_thenBlocked() {
        simulateFailedLogins(MAX_FAILED_CLIENT_ATTEMPTS - 1);
        assertLoginAllowed(true, "Allowed before reaching client-user max attempts");

        simulateFailedLogins(1);
        assertLoginAllowed(false, "Blocked after reaching client-user max attempts");

        simulateFailedLogins(1);
        assertLoginAllowed(false, "Still blocked after exceeding client-user max attempts");
    }

    @Test
    void givenFailedAttempts_whenLoginSucceeds_thenAttemptsClearedAndLoginAllowed() {
        simulateFailedLogins(MAX_FAILED_CLIENT_ATTEMPTS - 1);
        assertLoginAllowed(true, "Allowed before reaching max attempts");

        loginAttemptsService.loginSucceeded(TestRequestMetadataProvider.getClientInfo());
        assertLoginAllowed(true, "Allowed after successful login, attempts should be cleared");

        simulateFailedLogins(MAX_FAILED_CLIENT_ATTEMPTS -1);
        assertLoginAllowed(true, "Allowed after new attempts less than max (post success)");

        simulateFailedLogins(1);
        assertLoginAllowed(false, "Blocked after new attempts reach max (post success)");
    }

    // Helper for tests that modify client ID or IP randomly
    private boolean simulateAndCheckLoginWithRandomizedClient(LoginAttemptsService serviceToTest) {
        TestRequestMetadataProvider.setBrowserCookie(RandomStringUtils.randomAlphabetic(10)); // Unique client
        serviceToTest.loginFailed(TestRequestMetadataProvider.getClientInfo());
        return serviceToTest.isAllowed(TestRequestMetadataProvider.getClientInfo());
    }

    private boolean simulateAndCheckLoginWithRandomizedClientAndIp(LoginAttemptsService serviceToTest) {
        TestRequestMetadataProvider.setBrowserCookie(RandomStringUtils.randomAlphabetic(10)); // Unique client
        TestRequestMetadataProvider.setIpAddress(RandomStringUtils.randomNumeric(3) + "." + RandomStringUtils.randomNumeric(3) + "." + RandomStringUtils.randomNumeric(3) + "." + RandomStringUtils.randomNumeric(3)); // Unique IP
        serviceToTest.loginFailed(TestRequestMetadataProvider.getClientInfo());
        return serviceToTest.isAllowed(TestRequestMetadataProvider.getClientInfo());
    }

    @Test
    void givenSameIpUser_whenClientChangesAndMaxIpAttemptsExceeded_thenBlocked() {
        // This test uses the default loginAttemptsService from setUp
        TestRequestMetadataProvider.setUserName("ipUserTest@example.com");
        TestRequestMetadataProvider.setIpAddress("10.0.0.1");

        for (int i = 0; i < MAX_FAILED_IP_ATTEMPTS - 1; i++) {
            assertThat(simulateAndCheckLoginWithRandomizedClient(loginAttemptsService))
                    .as("Login allowed for unique client, attempt %d for IP-User", i + 1)
                    .isTrue();
        }

        assertThat(simulateAndCheckLoginWithRandomizedClient(loginAttemptsService))
                .as("Login blocked for unique client, after %d attempts for IP-User", MAX_FAILED_IP_ATTEMPTS)
                .isFalse();
    }

    @Test
    void givenSameUser_whenClientAndIpChangeAndMaxUserAttemptsExceeded_thenBlocked() {
        // This test uses the default loginAttemptsService from setUp
        TestRequestMetadataProvider.setUserName("userOnlyTest@example.com");

        for (int i = 0; i < MAX_FAILED_USER_ATTEMPTS - 1; i++) {
            assertThat(simulateAndCheckLoginWithRandomizedClientAndIp(loginAttemptsService))
                    .as("Login allowed for unique client/IP, attempt %d for User", i + 1)
                    .isTrue();
        }
        assertThat(simulateAndCheckLoginWithRandomizedClientAndIp(loginAttemptsService))
                .as("Login blocked for unique client/IP, after %d attempts for User", MAX_FAILED_USER_ATTEMPTS)
                .isFalse();
    }

    // Removed static initRequestMetadata as it's handled by @BeforeEach/@AfterEach

    @Test
    void givenClientManuallyBlacklisted_whenLoginAttempted_thenBlocked() {
        loginAttemptsService.blacklistClient(TestRequestMetadataProvider.getClientInfo());
        assertLoginAllowed(false, "Login blocked after client is manually blacklisted");
    }

    @Test
    void givenAttemptsMade_whenCacheExpires_thenAttemptCountResets() {
        FakeTicker fakeTicker = new FakeTicker();
        // Need a specific LoginAttemptsService instance that uses this fakeTicker
        LoginAttemptsService expiringService = new LoginAttemptsService(defaultDecisionVoter, fakeTicker);

        TestRequestMetadataProvider.setUserName("expiryTestUser@example.com");

        simulateFailedLogins(expiringService, MAX_FAILED_CLIENT_ATTEMPTS - 1);
        assertLoginAllowed(expiringService, true, "Allowed before cache expiry");

        fakeTicker.advance(4, TimeUnit.HOURS);
        fakeTicker.advance(1, TimeUnit.MINUTES);

        // This first attempt after expiry should be treated as attempt #1
        simulateFailedLogins(expiringService, 1);
        assertLoginAllowed(expiringService, true, "Allowed after cache expiry and 1 new attempt");

        // Fill up to max attempts again
        simulateFailedLogins(expiringService, MAX_FAILED_CLIENT_ATTEMPTS - 2); // Already made 1, so N-2 more
        assertLoginAllowed(expiringService, true, "Still allowed just before hitting max attempts post-expiry");

        simulateFailedLogins(expiringService, 1); // The one that blocks
        assertLoginAllowed(expiringService, false, "Blocked after hitting max attempts post-expiry");
    }

    @Test
    void givenBlankUsername_whenLoginFailedMultipleTimes_thenNoAttemptsRecordedAndLoginAllowed() {
        TestRequestMetadataProvider.setUserName("");
        simulateFailedLogins(MAX_FAILED_USER_ATTEMPTS + 5); // More than any threshold
        assertLoginAllowed(true, "Login allowed with blank username despite many failures");
    }

    @Test
    void givenBlankClientId_whenMaxAttemptsForClientUserExceeded_thenBlocked() {
        TestRequestMetadataProvider.setBrowserCookie("");
        TestRequestMetadataProvider.setUserName("userForBlankClientTest@example.com");

        simulateFailedLogins(MAX_FAILED_CLIENT_ATTEMPTS - 1);
        assertLoginAllowed(true, "Allowed before max attempts with blank client ID");

        simulateFailedLogins(1);
        assertLoginAllowed(false, "Blocked after max attempts with blank client ID");
    }

    @Test
    void givenBlankIpAddress_whenMaxAttemptsForIpUserExceeded_thenBlocked() {
        TestRequestMetadataProvider.setIpAddress("");
        TestRequestMetadataProvider.setUserName("userForBlankIpTest@example.com");
        // Ensure client ID is non-blank for this test to focus on IP behavior
        TestRequestMetadataProvider.setBrowserCookie("clientForBlankIpScenario");


        simulateFailedLogins(MAX_FAILED_IP_ATTEMPTS - 1);
        assertLoginAllowed(true, "Allowed before max IP attempts with blank IP address");

        simulateFailedLogins(1);
        assertLoginAllowed(false, "Blocked after max IP attempts with blank IP address");
    }


}