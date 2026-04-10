package com.softropic.payam.orange.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orange")
public class OrangeMoneyConfig {
    private String baseUrl;
    private String payUrl;
    private String tokenUrl;
    private String consumerKey;
    private String consumerSecret;
    private int payTokenExpiryThresholdMinutes = 8;
    private Poller poller = new Poller();
    private List<String> callbackIpWhitelist = new ArrayList<>();
    private String callbackUrl;  // orange.callback-url

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

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getPayUrl() { return payUrl; }
    public void setPayUrl(String payUrl) { this.payUrl = payUrl; }

    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }

    public String getConsumerKey() { return consumerKey; }
    public void setConsumerKey(String consumerKey) { this.consumerKey = consumerKey; }

    public String getConsumerSecret() { return consumerSecret; }
    public void setConsumerSecret(String consumerSecret) { this.consumerSecret = consumerSecret; }

    public int getPayTokenExpiryThresholdMinutes() { return payTokenExpiryThresholdMinutes; }
    public void setPayTokenExpiryThresholdMinutes(int payTokenExpiryThresholdMinutes) {
        this.payTokenExpiryThresholdMinutes = payTokenExpiryThresholdMinutes;
    }

    public Poller getPoller() { return poller; }
    public void setPoller(Poller poller) { this.poller = poller; }

    public List<String> getCallbackIpWhitelist() { return callbackIpWhitelist; }
    public void setCallbackIpWhitelist(List<String> callbackIpWhitelist) { this.callbackIpWhitelist = callbackIpWhitelist; }

    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
}
