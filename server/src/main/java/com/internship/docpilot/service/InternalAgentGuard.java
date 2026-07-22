package com.internship.docpilot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.model.AppUser;
import com.internship.docpilot.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Authenticates the private Agent-to-business API boundary. */
@Component
public class InternalAgentGuard {
  private final byte[] expectedKey;
  private final byte[] identitySecret;
  private final boolean identityRequired;
  private final String identityIssuer;
  private final String identityAudience;
  private final String identityKeyId;
  private final long identityMaxTtlSeconds;
  private final UserRepository users;
  private final ObjectMapper objectMapper;

  public InternalAgentGuard(
      @Value("${app.agent.internal-key}") String expectedKey,
      @Value("${app.agent.internal-identity-secret:}") String identitySecret,
      @Value("${app.agent.internal-identity-required:false}") boolean identityRequired,
      @Value("${app.agent.internal-identity-issuer:docpilot-agent}") String identityIssuer,
      @Value("${app.agent.internal-identity-audience:docpilot-server}") String identityAudience,
      @Value("${app.agent.internal-identity-key-id:v1}") String identityKeyId,
      @Value("${app.agent.internal-identity-max-ttl-seconds:600}") long identityMaxTtlSeconds,
      UserRepository users,
      ObjectMapper objectMapper) {
    if (identityRequired && (identitySecret == null || identitySecret.isBlank())) {
      throw new IllegalArgumentException(
          "AGENT_INTERNAL_IDENTITY_SECRET is required when signed identity is enforced");
    }
    this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
    this.identitySecret =
        identitySecret == null ? new byte[0] : identitySecret.getBytes(StandardCharsets.UTF_8);
    this.identityRequired = identityRequired;
    this.identityIssuer = identityIssuer;
    this.identityAudience = identityAudience;
    this.identityKeyId = identityKeyId;
    this.identityMaxTtlSeconds = Math.max(60, identityMaxTtlSeconds);
    this.users = users;
    this.objectMapper = objectMapper;
  }

  /** Compatibility overload for local tests and legacy development deployments. */
  public AppUser authenticate(String suppliedKey, String username) {
    return authenticate(suppliedKey, username, null);
  }

  public AppUser authenticate(
      String suppliedKey, String legacyUsername, String identityToken) {
    byte[] supplied =
        suppliedKey == null ? new byte[0] : suppliedKey.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expectedKey, supplied)) {
      throw unauthorized("Invalid internal Agent credential");
    }

    String username = legacyUsername;
    if (identityToken != null && !identityToken.isBlank()) {
      if (identitySecret.length == 0) {
        throw unauthorized("Signed Agent identity is not configured");
      }
      username = verifyIdentity(identityToken);
      if (legacyUsername != null
          && !legacyUsername.isBlank()
          && !MessageDigest.isEqual(
              username.getBytes(StandardCharsets.UTF_8),
              legacyUsername.getBytes(StandardCharsets.UTF_8))) {
        throw unauthorized("Signed identity does not match the compatibility username");
      }
    } else if (identityRequired) {
      throw unauthorized("A short-lived signed Agent identity is required");
    }

    AppUser user = username == null ? null : users.find(username);
    if (user == null || !user.isEnabled()) {
      throw new BusinessException(
          HttpStatus.FORBIDDEN, "The Agent identity does not map to an enabled user");
    }
    return user;
  }

  private String verifyIdentity(String token) {
    try {
      String[] parts = token.split("\\.", -1);
      if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
        throw new IllegalArgumentException("token format");
      }
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(identitySecret, "HmacSHA256"));
      byte[] expectedSignature =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encode(mac.doFinal(parts[0].getBytes(StandardCharsets.UTF_8)));
      if (!MessageDigest.isEqual(
          expectedSignature, parts[1].getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException("signature");
      }

      Map<String, Object> payload =
          objectMapper.readValue(
              Base64.getUrlDecoder().decode(parts[0]),
              new TypeReference<Map<String, Object>>() {});
      long now = Instant.now().getEpochSecond();
      long issuedAt = number(payload, "iat");
      long notBefore = number(payload, "nbf");
      long expiresAt = number(payload, "exp");
      String subject = string(payload, "sub");
      if (!identityIssuer.equals(string(payload, "iss"))
          || !identityAudience.equals(string(payload, "aud"))
          || !identityKeyId.equals(string(payload, "kid"))
          || string(payload, "jti").length() < 8
          || subject.isBlank()
          || issuedAt <= 0
          || issuedAt > now + 30
          || notBefore > now + 30
          || expiresAt <= issuedAt
          || expiresAt < now - 30
          || expiresAt - issuedAt > identityMaxTtlSeconds) {
        throw new IllegalArgumentException("claims");
      }
      return subject;
    } catch (GeneralSecurityException | java.io.IOException | RuntimeException invalid) {
      throw unauthorized("Invalid short-lived Agent identity");
    }
  }

  private long number(Map<String, Object> payload, String name) {
    Object value = payload.get(name);
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  private String string(Map<String, Object> payload, String name) {
    Object value = payload.get(name);
    return value == null ? "" : String.valueOf(value).trim();
  }

  private BusinessException unauthorized(String message) {
    return new BusinessException(HttpStatus.UNAUTHORIZED, message);
  }
}
