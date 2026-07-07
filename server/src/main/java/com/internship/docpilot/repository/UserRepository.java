package com.internship.docpilot.repository;

import com.internship.docpilot.model.AppUser;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
  private final JdbcTemplate jdbc;

  public UserRepository(JdbcTemplate j) {
    jdbc = j;
  }

  public AppUser find(String name) {
    List<AppUser> x =
        jdbc.query(
            "SELECT id,username,password_hash,display_name,role,enabled FROM app_user WHERE username=?",
            new Object[] {name},
            (r, i) -> {
              AppUser u = new AppUser();
              u.setId(r.getLong("id"));
              u.setUsername(r.getString("username"));
              u.setPasswordHash(r.getString("password_hash"));
              u.setDisplayName(r.getString("display_name"));
              u.setRole(r.getString("role"));
              u.setEnabled(r.getBoolean("enabled"));
              return u;
            });
    return x.isEmpty() ? null : x.get(0);
  }
}
