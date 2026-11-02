package com.shopstream.payment.provider.paypal;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * A real payment gateway call that hangs forever is worse than one that
 * fails fast -- a stuck HTTP call here would tie up a Kafka consumer thread
 * indefinitely. Built on plain java.net.http.HttpClient (connect timeout)
 * wrapped in Spring's JdkClientHttpRequestFactory (adds the read timeout),
 * rather than Boot's newer auto-configured RestClient factory helpers, since
 * those have shifted across recent Boot releases and these two classes are
 * foundational enough to be a safer bet.
 */
@Configuration
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "paypal")
public class PayPalRestClientConfig {

    @Bean
    public RestClient payPalRestClient(PayPalProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
