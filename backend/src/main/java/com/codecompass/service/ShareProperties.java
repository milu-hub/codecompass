package com.codecompass.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** F6 分享配置。 */
@ConfigurationProperties(prefix = "codecompass.share")
public class ShareProperties {

    /** 快照过期天数。 */
    private int expiresDays = 30;

    public int getExpiresDays() {
        return expiresDays;
    }

    public void setExpiresDays(int expiresDays) {
        this.expiresDays = expiresDays;
    }
}
