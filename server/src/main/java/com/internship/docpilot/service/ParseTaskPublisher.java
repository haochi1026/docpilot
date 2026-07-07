package com.internship.docpilot.service;

public interface ParseTaskPublisher {
  void publish(Long documentId) throws Exception;
}
