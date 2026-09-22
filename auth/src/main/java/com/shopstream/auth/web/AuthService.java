package com.shopstream.auth.web;

import com.shopstream.auth.dto.AuthResponse;
import com.shopstream.auth.dto.LoginRequest;
import com.shopstream.auth.dto.RegisterRequest;
import com.shopstream.auth.exception.EmailAlreadyExistsException;
import com.shopstream.auth.exception.InvalidCredentialsException;
import com.shopstream.auth.security.JwtService;
import com.shopstream.auth.user.User;
import com.shopstream.auth.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }
        User user = User.newCustomer(request.email(), passwordEncoder.encode(request.password()));
        userRepository.save(user);
        return issueToken(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Same exception/message as "user not found" above -- never reveal
            // which part of the credential pair was wrong.
            throw new InvalidCredentialsException();
        }
        return issueToken(user);
    }

    private AuthResponse issueToken(User user) {
        String token = jwtService.generateAccessToken(user);
        return AuthResponse.bearer(token, jwtService.expirationSeconds());
    }
}
