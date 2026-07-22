package com.internship.docpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.model.AppUser;
import com.internship.docpilot.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InternalAgentGuardTest {
  @Mock private UserRepository users;

  @Test
  void authenticatesByInternalKeyAndDatabaseUser() {
    AppUser user = new AppUser();
    user.setUsername("student");
    user.setEnabled(true);
    when(users.find("student")).thenReturn(user);
    InternalAgentGuard guard = guard(false);
    assertEquals(user, guard.authenticate("secret", "student"));
  }

  @Test
  void rejectsWrongKeyBeforeUsingModelSuppliedIdentity() {
    InternalAgentGuard guard = guard(false);
    assertThrows(BusinessException.class, () -> guard.authenticate("wrong", "student"));
  }

  @Test
  void rejectsDisabledDatabaseIdentity() {
    AppUser user = new AppUser();
    user.setUsername("student");
    user.setEnabled(false);
    when(users.find("student")).thenReturn(user);
    InternalAgentGuard guard = guard(false);
    assertThrows(BusinessException.class, () -> guard.authenticate("secret", "student"));
  }

  @Test
  void authenticatesShortLivedSignedIdentityAndChecksDatabaseUser() throws Exception {
    AppUser user = new AppUser();
    user.setUsername("student");
    user.setEnabled(true);
    when(users.find("student")).thenReturn(user);
    String token = signedIdentity("student");

    assertEquals(user, guard(true).authenticate("secret", null, token));
  }

  @Test
  void rejectsTamperedOrMissingSignedIdentityWhenRequired() throws Exception {
    String token = signedIdentity("student");
    InternalAgentGuard guard = guard(true);
    assertThrows(
        BusinessException.class,
        () -> guard.authenticate("secret", "student", token + "tampered"));
    assertThrows(
        BusinessException.class, () -> guard.authenticate("secret", "student", null));
  }

  private InternalAgentGuard guard(boolean required) {
    return new InternalAgentGuard(
        "secret",
        "identity-secret",
        required,
        "docpilot-agent",
        "docpilot-server",
        "v1",
        600,
        users,
        new ObjectMapper());
  }

  private String signedIdentity(String subject) throws Exception {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("iss", "docpilot-agent");
    payload.put("aud", "docpilot-server");
    payload.put("kid", "v1");
    payload.put("jti", "test-token-123456");
    payload.put("sub", subject);
    payload.put("iat", now);
    payload.put("nbf", now - 1);
    payload.put("exp", now + 300);
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(new ObjectMapper().writeValueAsBytes(payload));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("identity-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
    return encoded + "." + signature;
  }
}
