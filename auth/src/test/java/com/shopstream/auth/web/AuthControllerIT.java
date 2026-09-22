package com.shopstream.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.shopstream.auth.dto.AuthResponse;
import com.shopstream.auth.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises register -> login -> duplicate-email -> wrong-password against a
 * real MySQL instance (via Testcontainers) and Flyway migrations, not H2 or
 * mocks. This is the same MySQL major version used in docker-compose.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AuthControllerIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void registerThenLoginReturnsAValidBearerToken() {
        RegisterRequest request = new RegisterRequest("jane@example.com", "correct-horse-battery");

        ResponseEntity<AuthResponse> registerResponse =
                restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);

        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResponse.getBody()).isNotNull();
        assertThat(registerResponse.getBody().accessToken()).isNotBlank();
        assertThat(registerResponse.getBody().tokenType()).isEqualTo("Bearer");

        var loginRequest = new com.shopstream.auth.dto.LoginRequest("jane@example.com", "correct-horse-battery");
        ResponseEntity<AuthResponse> loginResponse =
                restTemplate.postForEntity("/api/auth/login", loginRequest, AuthResponse.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        assertThat(loginResponse.getBody().accessToken()).isNotBlank();
    }

    @Test
    void registeringTheSameEmailTwiceReturnsConflict() {
        RegisterRequest request = new RegisterRequest("dup@example.com", "correct-horse-battery");
        restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);

        ResponseEntity<AuthResponse> secondAttempt =
                restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);

        assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthorized() {
        RegisterRequest request = new RegisterRequest("wrongpw@example.com", "correct-horse-battery");
        restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);

        var badLogin = new com.shopstream.auth.dto.LoginRequest("wrongpw@example.com", "totally-wrong");
        ResponseEntity<AuthResponse> response =
                restTemplate.postForEntity("/api/auth/login", badLogin, AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithUnknownEmailReturnsUnauthorizedNotNotFound() {
        var unknownLogin = new com.shopstream.auth.dto.LoginRequest("nobody@example.com", "whatever123");

        ResponseEntity<AuthResponse> response =
                restTemplate.postForEntity("/api/auth/login", unknownLogin, AuthResponse.class);

        // 401, not 404 -- we never reveal whether an email is registered.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
