package com.internship.docpilot.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DemoDataInitializer implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;

  public DemoDataInitializer(JdbcTemplate jdbc, PasswordEncoder encoder) {
    this.jdbc = jdbc;
    this.encoder = encoder;
  }

  @Override
  public void run(ApplicationArguments args) {
    ensureUser("student", "演示用户", "USER");
    ensureUser("admin", "平台管理员", "ADMIN");
    ensureUser("manager", "算法组主管", "MANAGER");
    ensureUser("researcher", "项目协作者", "USER");
    Long student = userId("student");
    Long manager = userId("manager");
    Long researcher = userId("researcher");
    jdbc.update("INSERT IGNORE INTO department(name) VALUES('算法研究组')");
    Long department =
        jdbc.queryForObject("SELECT id FROM department WHERE name='算法研究组'", Long.class);
    jdbc.update(
        "INSERT INTO department_member(department_id,user_id,member_role) VALUES(?,?,'MANAGER') "
            + "ON DUPLICATE KEY UPDATE member_role='MANAGER'",
        department,
        manager);
    jdbc.update(
        "INSERT INTO department_member(department_id,user_id,member_role) VALUES(?,?,'MEMBER') "
            + "ON DUPLICATE KEY UPDATE member_role='MEMBER'",
        department,
        student);
    jdbc.update(
        "INSERT INTO department_member(department_id,user_id,member_role) VALUES(?,?,'MEMBER') "
            + "ON DUPLICATE KEY UPDATE member_role='MEMBER'",
        department,
        researcher);
    Integer k = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_base", Integer.class);
    if (k != null && k == 0) {
      jdbc.update(
          "INSERT INTO knowledge_base(name,description,owner_id,visibility) VALUES(?,?,?,'PRIVATE')",
          "课题组资料库",
          "论文、实验记录与项目规范",
          manager);
    }
    jdbc.update(
        "INSERT INTO kb_acl(kb_id,subject_type,subject_id,permission) "
            + "SELECT id,'USER',?,'READ' FROM knowledge_base WHERE owner_id=? "
            + "ON DUPLICATE KEY UPDATE permission='READ'",
        student,
        student);
    jdbc.update("UPDATE knowledge_base SET owner_id=? WHERE owner_id=?", manager, student);
    Long kb =
        jdbc.queryForObject(
            "SELECT id FROM knowledge_base WHERE name='课题组资料库' ORDER BY id LIMIT 1",
            Long.class);
    jdbc.update(
        "INSERT INTO kb_department(kb_id,department_id) "
            + "SELECT id,? FROM knowledge_base WHERE owner_id=? "
            + "ON DUPLICATE KEY UPDATE department_id=VALUES(department_id)",
        department,
        manager);
    jdbc.update(
        "INSERT INTO kb_acl(kb_id,subject_type,subject_id,permission) VALUES(?,'USER',?,'READ') "
            + "ON DUPLICATE KEY UPDATE permission='READ'",
        kb,
        student);
    jdbc.update(
        "INSERT INTO kb_acl(kb_id,subject_type,subject_id,permission) VALUES(?,'USER',?,'MANAGE') "
            + "ON DUPLICATE KEY UPDATE permission='MANAGE'",
        kb,
        researcher);
  }

  private void ensureUser(String username, String displayName, String role) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE username=?",
            new Object[] {username},
            Integer.class);
    if (count != null && count == 0) {
      jdbc.update(
          "INSERT INTO app_user(username,password_hash,display_name,role) VALUES(?,?,?,?)",
          username,
          encoder.encode("123456"),
          displayName,
          role);
    } else {
      jdbc.update(
          "UPDATE app_user SET display_name=?,role=?,enabled=1 WHERE username=?",
          displayName,
          role,
          username);
    }
  }

  private Long userId(String username) {
    return jdbc.queryForObject(
        "SELECT id FROM app_user WHERE username=?", new Object[] {username}, Long.class);
  }
}
