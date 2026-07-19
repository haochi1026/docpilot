package com.internship.docpilot.model;

public class PageText {
  private final Integer pageNo;
  private final String text;
  private final boolean ocr;

  public PageText(Integer pageNo, String text, boolean ocr) {
    this.pageNo = pageNo;
    this.text = text;
    this.ocr = ocr;
  }

  public Integer getPageNo() {
    return pageNo;
  }

  public String getText() {
    return text;
  }

  public boolean isOcr() {
    return ocr;
  }
}
