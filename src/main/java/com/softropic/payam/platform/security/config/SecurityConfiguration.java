package com.softropic.payam.platform.security.config;


import com.softropic.payam.platform.security.audit.filter.LoggingFilter;
import com.softropic.payam.platform.security.service.LoginTokenManager;
import com.softropic.payam.platform.security.infrastructure.filter.SecondFactorLoginFilter;
import com.softropic.payam.platform.security.infrastructure.filter.SecurityAdviceFilter;
import com.softropic.payam.platform.security.infrastructure.filter.SessionRefreshFilter;
import com.softropic.payam.platform.security.infrastructure.AjaxLogoutSuccessHandler;
import com.softropic.payam.platform.security.infrastructure.ApplicationAccessDeniedHandler;
import com.softropic.payam.platform.security.infrastructure.AuthenticationExceptionHandler;
import com.softropic.payam.platform.security.common.util.RequestMetadata;
import com.softropic.payam.platform.security.service.SecurityUtil;
import com.softropic.payam.platform.security.infrastructure.jwt.filter.JWTAuthenticationFilter;
import com.softropic.payam.platform.security.infrastructure.jwt.filter.JWTAuthorizationFilter;
import com.softropic.payam.platform.security.infrastructure.AuthenticationManagerSimulator;
import com.softropic.payam.platform.security.service.ClientIdAccessDecisionManager;
import com.softropic.payam.platform.security.infrastructure.FraudAwareAuthenticationManager;
import com.softropic.payam.platform.security.service.LoginDecisionManager;
import com.softropic.payam.platform.security.infrastructure.SecuredHttpEndpointGuard;
import com.softropic.payam.platform.security.service.TwoFactorLoginService;
import com.softropic.payam.platform.security.infrastructure.UnanimousAuthorizationManager;
import com.softropic.payam.platform.security.service.DaoAuthProvider;
import com.softropic.payam.platform.security.service.LoadUserByUserNameService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import com.softropic.payam.platform.security.contract.SecurityProperties;

import java.util.List;

import static com.softropic.payam.platform.security.common.util.SecurityConstants.ADMIN_COOKIE;
import static com.softropic.payam.platform.security.common.util.SecurityConstants.B_COOKIE;
import static com.softropic.payam.platform.security.common.util.SecurityConstants.JAVA_SESSION_COOKIE;
import static com.softropic.payam.platform.security.common.util.SecurityConstants.JWT_COOKIE_NAME;
import static com.softropic.payam.platform.security.common.util.SecurityConstants.USER_COOKIE;
import static com.softropic.payam.platform.security.config.AppEndpoints.PUBLIC_ENDPOINTS;
import static com.softropic.payam.platform.security.config.AppEndpoints.PUBLIC_STATIC_RESOURCES;
import static com.softropic.payam.platform.security.config.AppEndpoints.SECURED_ENDPOINTS;


@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true,
                      jsr250Enabled = true)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    @Bean
    public DaoAuthProvider authProvider(LoadUserByUserNameService loadUserByUserNameService) {
        final DaoAuthProvider authProvider = new DaoAuthProvider();
        authProvider.setUserDetailsService(loadUserByUserNameService);
        authProvider.setPasswordEncoder(passwordEncoder());
        authProvider.setPreAuthenticationChecks(new AccountStatusUserDetailsChecker());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(DaoAuthProvider daoAuthProvider) {
        return new ProviderManager(daoAuthProvider);
    }

    @Bean
    public AuthorizationManager<RequestAuthorizationContext> authorizationManager(ClientIdAccessDecisionManager clientIdAccessDecisionManager,
                                                                                  Environment env) {
        //TODO hasAnyRole is used for roles without the "ROLE_" prefix
        final AuthorityAuthorizationManager<RequestAuthorizationContext> aaManager = AuthorityAuthorizationManager.hasAnyAuthority(AppEndpoints.SECURED_MAPPINGS.get(AppEndpoints.SECURED));
        final List<AuthorizationManager<RequestAuthorizationContext>> authManagers = activateSecurity(env) ? List.of(
                clientIdAccessDecisionManager,
                aaManager) : List.of(clientIdAccessDecisionManager);
        return new UnanimousAuthorizationManager(authManagers);
    }

    @SuppressWarnings("PMD")
    public FraudAwareAuthenticationManager fraudAwareAuthenticationManager(AuthenticationManager authenticationManager,
                                                                           LoginDecisionManager<RequestMetadata> loginDecisionManager) {
        return new FraudAwareAuthenticationManager(authenticationManager,
                                                   loginDecisionManager,
                                                   new AuthenticationManagerSimulator(passwordEncoder()));
    }

    private static boolean activateSecurity(Environment env) {
        return env.getProperty("activate.security", Boolean.class, true);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); //The default encryption round count is 10
    }

    @Bean
    public AuthenticationEventPublisher authEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new DefaultAuthenticationEventPublisher(applicationEventPublisher);
    }

    @Bean
    public List<String> allowedClients(@Value("#{'${allowed.clients}'.split(',')}") final List<String> allowedClients) {
        return allowedClients;
    }

    /**
     * This filter kicks in conditionally. i.e if a request comes in with an X-Forwarded-* or Forwarded header
     * It helps when the app is behind a load balancer or proxy
     * @return
     */
    @Bean
    public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
        ForwardedHeaderFilter filter = new ForwardedHeaderFilter();
        FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(final HttpSecurity http,
                                           AjaxLogoutSuccessHandler ajaxLogoutSuccessHandler,
                                           SecurityAdviceFilter securityAdviceFilter,
                                           TwoFactorLoginService twoFactorLoginManager,
                                           HandlerExceptionResolver handlerExceptionResolver,
                                           AuthenticationManager authenticationManager,
                                           DaoAuthProvider daoAuthProvider,
                                           AuthorizationManager<RequestAuthorizationContext> authorizationManager,
                                           ApplicationEventPublisher applicationEventPublisher,
                                           LoginTokenManager loginTokenManager,
                                           SecurityUtil securityUtil,
                                           CorsConfiguration corsConfiguration,
                                           @Qualifier("loginAttemptService") LoginDecisionManager<RequestMetadata> loginDecisionManager,
                                           Environment env) throws Exception {
        //TODO test first, then remove the following line
        //http.authorizeRequests().accessDecisionManager(accessDecisionManager);
        http.cors(cors -> cors.configurationSource(request -> corsConfiguration));

        //http.cors(Customizer.withDefaults()); //enable cors (configure it to block iframes from unknown third parties)
        //https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
        http.csrf(AbstractHttpConfigurer::disable);//not needed when using token-based authentication, such as JWT
        http.exceptionHandling(exceptionHandling -> {
                //used whenever access is denied (this is authorization and not authentication
            exceptionHandling.accessDeniedHandler(new ApplicationAccessDeniedHandler(handlerExceptionResolver));
                // handles AuthenticationException that may have by-passed the handler in JWTAuthenticationFilter.
                // So far, I have not found a by-pass path. However this could happen if a different authenticator other than JWTAuthenticationFilter is used
                exceptionHandling.authenticationEntryPoint(new AuthenticationExceptionHandler(handlerExceptionResolver));
        });
        http.formLogin(Customizer.withDefaults());
        http.logout(customizer ->
            customizer.logoutUrl("/api/logout")
                      .logoutSuccessHandler(ajaxLogoutSuccessHandler)
                      .deleteCookies(JAVA_SESSION_COOKIE,
                                     JWT_COOKIE_NAME,
                                     B_COOKIE,
                                     USER_COOKIE,
                                     ADMIN_COOKIE)
                      .permitAll()
        );
        http.headers(customizer -> customizer.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable));
        http.authorizeHttpRequests(configureRequestMatching(authorizationManager, env));
        http.addFilterAfter(new JWTAuthenticationFilter(fraudAwareAuthenticationManager(authenticationManager, loginDecisionManager),
                                                        applicationEventPublisher,
                                                        handlerExceptionResolver,
                                                        twoFactorLoginManager,
                                                        loginTokenManager),
                            UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new JWTAuthorizationFilter(daoAuthProvider,
                                                       applicationEventPublisher,
                                                       new SecuredHttpEndpointGuard(AppEndpoints.SECURED_MAPPINGS, AppEndpoints.ALL_UNRESTRICTED),
                                                       loginTokenManager,
                                                       securityUtil,
                                                       env),
                            BasicAuthenticationFilter.class)
            .addFilterBefore(securityAdviceFilter, WebAsyncManagerIntegrationFilter.class)
            .addFilterBefore(new LoggingFilter(PUBLIC_STATIC_RESOURCES),
                             SecurityAdviceFilter.class)
            .addFilterAfter(new SecondFactorLoginFilter(twoFactorLoginManager, loginTokenManager, applicationEventPublisher),
                            SecurityAdviceFilter.class)
            .addFilterAfter(new SessionRefreshFilter(AppEndpoints.REFRESH), JWTAuthorizationFilter.class);
        return http.build();
    }

    private static Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> configureRequestMatching(
            AuthorizationManager<RequestAuthorizationContext> authorizationManager, Environment env) {
        return (customizer) -> {
            //permit scraper
            customizer.requestMatchers(AppEndpoints.PUBLIC_MGMT_ENDPOINTS.toArray(new String[0])).permitAll();

            customizer.requestMatchers(PUBLIC_STATIC_RESOURCES.toArray(new String[0])).permitAll()
                      //.requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                      .requestMatchers(PUBLIC_ENDPOINTS.toArray(new String[0])).permitAll();
            if (activateSecurity(env)) {

                //.requestMatchers(AppEndpoints.SECURED).hasAnyAuthority(AppEndpoints.SECURED_MAPPINGS.get(AppEndpoints.SECURED))
                customizer.requestMatchers(SECURED_ENDPOINTS.toArray(new String[0])).access(authorizationManager);
            }
            else {
                customizer.requestMatchers(SECURED_ENDPOINTS.toArray(new String[0])).permitAll();
            }
        };
    }


    //@Bean
    /** Seems to be added conditionally if spring data is also on the classpath
     * Exposes Spring Security as SpEL expressions for creating Spring Data queries
     * @Query("select m from Message m where m.to.id = ?#{ principal?.id }")
     *

    public SecurityEvaluationContextExtension securityEvaluationContextExtension() {
    return new SecurityEvaluationContextExtension();
    }
     */

}
