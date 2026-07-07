package com.internship.docpilot.repository;

import com.internship.docpilot.model.KnowledgeBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeBaseRepository {
  private final JdbcTemplate jdbc;

  public KnowledgeBaseRepository(JdbcTemplate j) {
    jdbc = j;
  }

  public List<KnowledgeBase> accessible(Long userId, String role) {
    String sql =
        "SELECT kb.id,kb.name,kb.description,kb.owner_id,kb.visibility,owner.display_name owner_name,"
            + "dep.name department_name,(SELECT COUNT(*) FROM document d WHERE d.kb_id=kb.id) document_count,"
            + "CASE WHEN ?='ADMIN' OR kb.owner_id=? OR EXISTS(SELECT 1 FROM kb_department kd2 "
            + "JOIN department_member dm ON dm.department_id=kd2.department_id "
            + "WHERE kd2.kb_id=kb.id AND dm.user_id=? AND dm.member_role='MANAGER') "
            + "OR EXISTS(SELECT 1 FROM kb_acl ma WHERE ma.kb_id=kb.id AND ma.subject_type='USER' "
            + "AND ma.subject_id=? AND ma.permission='MANAGE') THEN 'MANAGE' ELSE 'READ' END permission "
            + "FROM knowledge_base kb JOIN app_user owner ON owner.id=kb.owner_id "
            + "LEFT JOIN kb_department kd ON kd.kb_id=kb.id LEFT JOIN department dep ON dep.id=kd.department_id "
            + "WHERE kb.owner_id=? OR kb.visibility='PUBLIC' OR ?='ADMIN' "
            + "OR EXISTS(SELECT 1 FROM kb_acl a WHERE a.kb_id=kb.id AND a.subject_type='USER' AND a.subject_id=?) "
            + "OR EXISTS(SELECT 1 FROM kb_department kd3 JOIN department_member dm3 ON dm3.department_id=kd3.department_id "
            + "WHERE kd3.kb_id=kb.id AND dm3.user_id=? AND dm3.member_role='MANAGER') ORDER BY kb.id";
    return jdbc.query(
        sql,
        new Object[] {role, userId, userId, userId, userId, role, userId, userId},
        (r, i) -> map(r));
  }

  public boolean canRead(Long kbId, Long userId, String role) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_base kb WHERE kb.id=? AND (kb.owner_id=? OR kb.visibility='PUBLIC' OR ?='ADMIN' "
                + "OR EXISTS(SELECT 1 FROM kb_acl a WHERE a.kb_id=kb.id AND a.subject_type='USER' AND a.subject_id=?) "
                + "OR EXISTS(SELECT 1 FROM kb_department kd JOIN department_member dm ON dm.department_id=kd.department_id "
                + "WHERE kd.kb_id=kb.id AND dm.user_id=? AND dm.member_role='MANAGER'))",
            new Object[] {kbId, userId, role, userId, userId},
            Integer.class);
    return n != null && n > 0;
  }

  public boolean canManage(Long kbId, Long userId, String role) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_base kb WHERE kb.id=? AND (kb.owner_id=? OR ?='ADMIN' "
                + "OR EXISTS(SELECT 1 FROM kb_acl a WHERE a.kb_id=kb.id AND a.subject_type='USER' "
                + "AND a.subject_id=? AND a.permission='MANAGE') "
                + "OR EXISTS(SELECT 1 FROM kb_department kd JOIN department_member dm ON dm.department_id=kd.department_id "
                + "WHERE kd.kb_id=kb.id AND dm.user_id=? AND dm.member_role='MANAGER'))",
            new Object[] {kbId, userId, role, userId, userId},
            Integer.class);
    return n != null && n > 0;
  }

  public Long create(Long ownerId, String name, String description, Long departmentId) {
    KeyHolder h = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          PreparedStatement p =
              c.prepareStatement(
                  "INSERT INTO knowledge_base(name,description,owner_id,visibility) VALUES(?,?,?,'PRIVATE')",
                  Statement.RETURN_GENERATED_KEYS);
          p.setString(1, name);
          p.setString(2, description);
          p.setLong(3, ownerId);
          return p;
        },
        h);
    Long id = h.getKey().longValue();
    if (departmentId != null) {
      jdbc.update("INSERT INTO kb_department(kb_id,department_id) VALUES(?,?)", id, departmentId);
    }
    return id;
  }

  public Long managedDepartment(Long userId) {
    List<Long> rows =
        jdbc.query(
            "SELECT department_id FROM department_member WHERE user_id=? AND member_role='MANAGER' ORDER BY id LIMIT 1",
            new Object[] {userId},
            (r, i) -> r.getLong("department_id"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  public List<Map<String, Object>> members(Long kbId) {
    return jdbc.query(
        "SELECT u.id user_id,u.username,u.display_name,'OWNER' permission,1 fixed_member "
            + "FROM knowledge_base kb JOIN app_user u ON u.id=kb.owner_id WHERE kb.id=? "
            + "UNION ALL SELECT u.id,u.username,u.display_name,a.permission,0 "
            + "FROM kb_acl a JOIN app_user u ON u.id=a.subject_id "
            + "WHERE a.kb_id=? AND a.subject_type='USER' ORDER BY fixed_member DESC,display_name",
        new Object[] {kbId, kbId},
        (r, i) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("userId", r.getLong("user_id"));
          item.put("username", r.getString("username"));
          item.put("displayName", r.getString("display_name"));
          item.put("permission", r.getString("permission"));
          item.put("fixed", r.getBoolean("fixed_member"));
          return item;
        });
  }

  public Long userId(String username) {
    List<Long> rows =
        jdbc.query(
            "SELECT id FROM app_user WHERE username=? AND enabled=1",
            new Object[] {username},
            (r, i) -> r.getLong("id"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  public boolean isOwner(Long kbId, Long userId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_base WHERE id=? AND owner_id=?",
            new Object[] {kbId, userId},
            Integer.class);
    return count != null && count > 0;
  }

  public void grant(Long kbId, Long userId, String permission) {
    jdbc.update(
        "INSERT INTO kb_acl(kb_id,subject_type,subject_id,permission) VALUES(?,'USER',?,?) "
            + "ON DUPLICATE KEY UPDATE permission=VALUES(permission)",
        kbId,
        userId,
        permission);
  }

  public int revoke(Long kbId, Long userId) {
    return jdbc.update(
        "DELETE FROM kb_acl WHERE kb_id=? AND subject_type='USER' AND subject_id=?", kbId, userId);
  }

  public void audit(
      Long kbId, Long operatorId, Long targetUserId, String action, String permission) {
    jdbc.update(
        "INSERT INTO kb_permission_audit(kb_id,operator_id,target_user_id,action_type,permission) VALUES(?,?,?,?,?)",
        kbId,
        operatorId,
        targetUserId,
        action,
        permission);
  }

  private KnowledgeBase map(java.sql.ResultSet r) throws java.sql.SQLException {
    KnowledgeBase k = new KnowledgeBase();
    k.setId(r.getLong("id"));
    k.setName(r.getString("name"));
    k.setDescription(r.getString("description"));
    k.setOwnerId(r.getLong("owner_id"));
    k.setVisibility(r.getString("visibility"));
    k.setDocumentCount(r.getInt("document_count"));
    k.setPermission(r.getString("permission"));
    k.setOwnerName(r.getString("owner_name"));
    k.setDepartmentName(r.getString("department_name"));
    return k;
  }
}
