package dev.jagadeesh.payflow.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
public class TokenController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, String scope) {
    }

    private final AuthenticationManager authManager;
    private final JwtEncoder encoder;
    private final SecurityProperties props;

    public TokenController(AuthenticationManager authManager, JwtEncoder encoder, SecurityProperties props) {
        this.authManager = authManager;
        this.encoder = encoder;
        this.props = props;
    }

    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody LoginRequest login) {
        var auth = authManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password()));
        String scope = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(" "));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("payflow-ledger")
                .subject(auth.getName())
                .issuedAt(now)
                .expiresAt(now.plus(props.tokenTtl()))
                .claim("scope", scope)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new TokenResponse(token, "Bearer", props.tokenTtl().toSeconds(), scope);
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail badCredentials() {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        p.setProperty("code", "BAD_CREDENTIALS");
        return p;
    }
}
