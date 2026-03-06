package com.softropic.payam.security.manager;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.base.Ticker;


import com.softropic.payam.security.exposed.util.RequestMetadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;


/**
 * Records failed logins and uses this information to determine if a login can be allowed or not.
 * On a general note, successful login undoes the failed ones. There are however things to take into consideration:
 *  <ul>
     <li>It should be possible to use a blocked client to log a different user. So use a combination of clientId and username</li>
     <li>The client id (browser cookie or apikey) could be faked or stolen, leading to unique ids per request. So also take Ips into account.</li>
     <li>Several people could share an ip address. So avoid blocking just by ip. Use ip and loginId</li>
     <li>Ip addresses can be faked. So keep a higher count of failed logins for a given user to delay this</li>
    </ul>
 *
 * Levels of locking
 * <ol>
 *     <li>By clientId and user</li>
 *     <li>By Ip and user</li>
 *     <li>By user</li>
 * </ol>
 */
//TODO document all changes that need to be made once for the application to be multi-node ready. This cache cannot be used for multi node apps
@Slf4j
@Service("loginAttemptService")
public class LoginAttemptsService implements LoginDecisionManager<RequestMetadata>,
                                             LoginAttemptConsumer<RequestMetadata> {

    /*
    The client (browser/api machine client) is blocked when login failure occurs 3 times from same client within a time frame
     */
    private static final int MAX_FAILED_CLIENT_ATTEMPTS = 3;


    /*
    The ip address is blocked when login failure occurs {@code MAX_FAILED_CLIENT_ATTEMPTS } times within a time frame
     */
    private static final int MAX_FAILED_IP_ATTEMPTS = MAX_FAILED_CLIENT_ATTEMPTS;


    /*
     A rogue client will be able to make {@code MAX_FAILED_CLIENT_ATTEMPTS + 2 } attempts when it fakes ip addresses and clientIds
     */
    private static final int MAX_FAILED_USER_ATTEMPTS = MAX_FAILED_CLIENT_ATTEMPTS + 2;

    private final LoadingCache<String, Integer> blacklistedClients;

    //only the userName (loginId) is used as key here
    private final LoadingCache<String, Integer> attemptsByUserCache;

    //ip_userName key
    private final LoadingCache<String, Integer> attemptsByIpUserCache;

    //clientId_userName key
    private final LoadingCache<String, Integer> attemptsByClientUserCache;

    private final ClientIdAccessDecisionManager clientIdAccessDecisionVoter;



    @Autowired
    public LoginAttemptsService(final ClientIdAccessDecisionManager clientIdAccessDecisionMgr) {
        this(clientIdAccessDecisionMgr, Ticker.systemTicker());
    }

    // New constructor for testing
    public LoginAttemptsService(final ClientIdAccessDecisionManager clientIdAccessDecisionVoter, final Ticker ticker) {
        attemptsByUserCache = buildCache(ticker);
        attemptsByIpUserCache = buildCache(ticker);
        attemptsByClientUserCache = buildCache(ticker);
        blacklistedClients = buildCache(ticker); // Assuming blacklistedClients also needs controlled expiry
        this.clientIdAccessDecisionVoter = clientIdAccessDecisionVoter;
    }

    @Override
    public void loginSucceeded(final RequestMetadata metadata) {
        log.info("################# Login succeeded. Clear failed login attempts");
        deRecordAttempts(metadata);
    }

    @Override
    public void loginFailed(final RequestMetadata metadata) {
        log.info("Login failed. about to Record failed login attempts");
        //username will be blank if userName validation fails
        if(StringUtils.isNotBlank(metadata.getUserName())) {
            recordAttempts(attemptsByUserCache, metadata.getUserName());
            recordAttempts(attemptsByIpUserCache, getIpUserKey(metadata));
            recordAttempts(attemptsByClientUserCache, getClientIdUserKey(metadata));
        }
    }

    @Override
    public boolean isAllowed(final RequestMetadata metadata) {
        log.info("################# Verify if login is allowed based on previous attempts");
        try {
            return  attemptsByClientUserCache.get(getClientIdUserKey(metadata)) < MAX_FAILED_CLIENT_ATTEMPTS
                    &&
                    isIpAndUserAllowed(metadata)
                    &&
                    attemptsByUserCache.get(metadata.getUserName()) < MAX_FAILED_USER_ATTEMPTS
                    &&
                    clientIdAccessDecisionVoter.isClientIdAllowed() //Explicitly invoke this since call does not go down to controller lever. AccessDecisionVoters are called by interceptors. (interceptors are called before controllers)
                    &&
                    clientNotBlacklisted(metadata.getClientIdentifier()) ;
        } catch (ExecutionException e) {
            return true;
        }
    }

    private boolean clientNotBlacklisted(final String clientId) throws ExecutionException {
        if(StringUtils.isNotBlank(clientId)) {
            return blacklistedClients.get(clientId) == 0;
        }
        //bcookie should actually not yet exist
        //If bcookie exists at login then it is a fraud
        //use clientjs to create cookie called fcookie (fingerprint)
        //When user authenticates, create the bcookie using the fcookie
        //The lifespan of the fcookie should be same as jwt
        //renew bcookie when renewing jwt
        return true;
    }

    /**
     * Uses client identifier to blacklist client.
     * @param metadata provides the client data.
     */
    @Override
    public void blacklistClient(final RequestMetadata metadata) { //TODO not yet making use of this to prevent users. Only recording.
        //TODO could create a table in db and add a service here to populate blacklisted clients and the reasons
        //Blacklisted clients will not have access to the system anymore regardless of the user they use
        log.warn("Fraud detection from client with the following metadata {}", metadata);
        //The user is probably not known so just the client is blacklisted.
        //The getClientIdentifier here may not exist e.g for a browser (or else many users will share the same key)
        if(StringUtils.isNotBlank(metadata.getClientIdentifier())) {
            recordAttempts(blacklistedClients, metadata.getClientIdentifier());
        }
    }

    public void unblacklistClient(final String clientId) {
        blacklistedClients.invalidate(clientId);
    }

    @Override
    public void unblockClient(final RequestMetadata metadata) {
        log.info("################# Unblock client");
        deRecordAttempts(metadata);
    }

    private String getClientIdUserKey(final RequestMetadata metadata) {
        return metadata.getClientIdentifier() + "_" + metadata.getUserName();
    }

    private String getIpUserKey(final RequestMetadata metadata) {
        String ipAddress = StringUtils.isBlank(metadata.getIpAddress()) ? "" : metadata.getIpAddress();
        return ipAddress + "_" + metadata.getUserName();
    }


    private void recordAttempts(final LoadingCache<String, Integer> cache, final String identifier) {
        int attempts;
        try {
            attempts = cache.get(identifier);
        } catch (ExecutionException e) {
            attempts = 0;
        }
        attempts++;
        cache.put(identifier, attempts);
    }

    /**
     * Removes all previously failed attempts of the client-user combination.
     * Since this count augmented the other caches, it has to decrement them on removal.
     * @param metadata holds the client info needed for decrementing the cache counts.
     */
    private void deRecordAttempts(final RequestMetadata metadata) {
        int attempts;
        final String clientIdUserKey = getClientIdUserKey(metadata);
        try {
            attempts = attemptsByClientUserCache.get(clientIdUserKey);
            attemptsByClientUserCache.invalidate(clientIdUserKey);
            if(attempts > 0) {
                //The failed attempts were equally recorded in the other 2 caches. Reduce them
                // NB This is not accurate but the best way to go about this
                // NB The user may have had failed logins using the same client but different ips
                // NB The net effect is that the reduction occurs. In most cases the user will use the same ip though
                // NB This will not cause much/any harm even if user previously logged from different ips
                countDownAttempts(attemptsByIpUserCache, getIpUserKey(metadata), attempts);
                countDownAttempts(attemptsByUserCache, metadata.getUserName(), attempts);
            }
        } catch (ExecutionException e) {
            log.debug("'{}' not found in 'attemptsByClientUserCache'.", clientIdUserKey, e);
        }
    }

    private void countDownAttempts(final LoadingCache<String, Integer> cache, final String identifier, final int count) {
        int attempts;
        try {
            attempts = cache.get(identifier);
            attempts = attempts - count;
            if(attempts > 0) {
                cache.put(identifier, attempts);
            } else {
                cache.invalidate(identifier);
            }
        } catch (ExecutionException e) {
            log.warn("Error occurred upon execution of countdown attempts", e);
        }
    }


    private boolean isIpAndUserAllowed(final RequestMetadata metadata) {
        //TODO also verify that ip is in whitelist
        try {
            return attemptsByIpUserCache.get(getIpUserKey(metadata)) < MAX_FAILED_IP_ATTEMPTS;
        } catch (ExecutionException e) {
            return true;
        }
    }

    // Modified buildCache to accept and use a Ticker
    private LoadingCache<String, Integer> buildCache(Ticker ticker) {
        return CacheBuilder.newBuilder()
                           .ticker(ticker) // Use the provided ticker
                           .expireAfterWrite(4, TimeUnit.HOURS)
                           .build(new CacheLoader<>() {
                    @SuppressWarnings("NullableProblems")
                    @Override
                    public Integer load(final String key) {
                        return 0;
                    }
                });
    }

}