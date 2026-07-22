package com.internship.docpilot.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** Builds bounded HTTP clients for model and embedding calls. */
public final class HttpClientFactory {
  private HttpClientFactory() {}

  public static RestTemplate bounded(int connectTimeoutMs, int readTimeoutMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Math.max(100, connectTimeoutMs));
    factory.setReadTimeout(Math.max(100, readTimeoutMs));
    return new RestTemplate(factory);
  }
}
