package com.softropic.payam.mtn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "mtn")
public class MtnMoMoConfig {

    private String collectionBaseUrl;
    private String disbursementBaseUrl;
    private String collectionTokenUrl;      // POST /collection/token/
    private String disbursementTokenUrl;    // POST /disbursement/token/
    private String apiUserId;
    private String apiKey;
    private String collectionSubscriptionKey;
    private String disbursementSubscriptionKey;
    private String targetEnvironment;       // "sandbox" or "production"
    private List<String> callbackIpWhitelist = new ArrayList<>();
    private Poller poller = new Poller();

    public static class Poller {
        private int initialDelaySeconds = 120;
        private int intervalSeconds = 300;
        private int maxAttempts = 15;

        public int getInitialDelaySeconds() { return initialDelaySeconds; }
        public void setInitialDelaySeconds(int initialDelaySeconds) { this.initialDelaySeconds = initialDelaySeconds; }

        public int getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    public String getCollectionBaseUrl() { return collectionBaseUrl; }
    public void setCollectionBaseUrl(String collectionBaseUrl) { this.collectionBaseUrl = collectionBaseUrl; }

    public String getDisbursementBaseUrl() { return disbursementBaseUrl; }
    public void setDisbursementBaseUrl(String disbursementBaseUrl) { this.disbursementBaseUrl = disbursementBaseUrl; }

    public String getCollectionTokenUrl() { return collectionTokenUrl; }
    public void setCollectionTokenUrl(String collectionTokenUrl) { this.collectionTokenUrl = collectionTokenUrl; }

    public String getDisbursementTokenUrl() { return disbursementTokenUrl; }
    public void setDisbursementTokenUrl(String disbursementTokenUrl) { this.disbursementTokenUrl = disbursementTokenUrl; }

    public String getApiUserId() { return apiUserId; }
    public void setApiUserId(String apiUserId) { this.apiUserId = apiUserId; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getCollectionSubscriptionKey() { return collectionSubscriptionKey; }
    public void setCollectionSubscriptionKey(String collectionSubscriptionKey) { this.collectionSubscriptionKey = collectionSubscriptionKey; }

    public String getDisbursementSubscriptionKey() { return disbursementSubscriptionKey; }
    public void setDisbursementSubscriptionKey(String disbursementSubscriptionKey) { this.disbursementSubscriptionKey = disbursementSubscriptionKey; }

    public String getTargetEnvironment() { return targetEnvironment; }
    public void setTargetEnvironment(String targetEnvironment) { this.targetEnvironment = targetEnvironment; }

    public List<String> getCallbackIpWhitelist() { return callbackIpWhitelist; }
    public void setCallbackIpWhitelist(List<String> callbackIpWhitelist) { this.callbackIpWhitelist = callbackIpWhitelist; }

    public Poller getPoller() { return poller; }
    public void setPoller(Poller poller) { this.poller = poller; }
}
