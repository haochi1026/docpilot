package com.internship.docpilot.service;

import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.model.AppUser;
import com.internship.docpilot.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class InternalAgentGuard {
  private final byte[] expectedKey;
  private final UserRepository users;

  public InternalAgentGuard(
      @Value("${app.agent.internal-key}") String expectedKey, UserRepository users) {
    this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
    this.users = users;
  }

  public AppUser authenticate(String suppliedKey, String username) {
    byte[] supplied =
        suppliedKey == null ? new byte[0] : suppliedKey.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expectedKey, supplied)) {
      throw new BusinessException(HttpStatus.UNAUTHORIZED, "Agent 内部调用凭证无效");
    }
    AppUser user = username == null ? null : users.find(username);
    if (user == null || !user.isEnabled()) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "Agent 对应用户不存在或已停用");
    }
    return user;
  }

}
