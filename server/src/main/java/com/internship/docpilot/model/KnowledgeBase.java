package com.internship.docpilot.model;

public class KnowledgeBase {
  private Long id;
  private String name, description, visibility, permission, ownerName, departmentName;
  private Long ownerId;
  private int documentCount;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public String getName() {
    return name;
  }

  public void setName(String v) {
    name = v;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String v) {
    description = v;
  }

  public String getVisibility() {
    return visibility;
  }

  public void setVisibility(String v) {
    visibility = v;
  }

  public Long getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(Long v) {
    ownerId = v;
  }

  public int getDocumentCount() {
    return documentCount;
  }

  public void setDocumentCount(int v) {
    documentCount = v;
  }

  public String getPermission() {
    return permission;
  }

  public void setPermission(String value) {
    permission = value;
  }

  public String getOwnerName() {
    return ownerName;
  }

  public void setOwnerName(String value) {
    ownerName = value;
  }

  public String getDepartmentName() {
    return departmentName;
  }

  public void setDepartmentName(String value) {
    departmentName = value;
  }
}
