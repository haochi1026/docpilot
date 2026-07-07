package com.internship.docpilot.dto;

import javax.validation.constraints.NotBlank;

public class CreateKbRequest {
  @NotBlank private String name;
  private String description;

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
}
