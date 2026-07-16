package com.internship.docpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.model.AppUser;
import com.internship.docpilot.repository.UserRepository;
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
    InternalAgentGuard guard = new InternalAgentGuard("secret", users);
    assertEquals(user, guard.authenticate("secret", "student"));
  }

  @Test
  void rejectsWrongKeyBeforeUsingModelSuppliedIdentity() {
    InternalAgentGuard guard = new InternalAgentGuard("secret", users);
    assertThrows(BusinessException.class, () -> guard.authenticate("wrong", "student"));
  }
}

