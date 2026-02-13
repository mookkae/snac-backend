package com.ureca.snac.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!loadtest")
@Data
@Component
@ConfigurationProperties(prefix = "custom.stomp")
public class StompRelayProperties {
    private String host;
    private int port;
    private String clientLogin;
    private String clientPasscode;
    private String systemLogin;
    private String systemPasscode;
}
