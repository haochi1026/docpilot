package com.internship.docpilot.config;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Fails fast instead of allowing demo credentials to reach a production process. */
@Configuration
public class ProductionConfigurationValidator {
  private static final Set<String> DEMO_VALUES =
      Set.of(
          "root123456",
          "minioadmin",
          "minioadmin123",
          "docpilot-vector-local-password",
          "docpilot-local-demo-secret-change-before-production",
          "change-this-docpilot-demo-secret-key-at-least-32-bytes",
          "change-this-agent-service-key",
          "change-this-agent-internal-key");

  public ProductionConfigurationValidator(
      @Value("${app.environment:development}") String environment,
      @Value("${app.jwt-secret:}") String jwtSecret,
      @Value("${spring.datasource.password:}") String databasePassword,
      @Value("${app.storage.access-key:}") String storageAccessKey,
      @Value("${app.storage.secret-key:}") String storageSecretKey,
      @Value("${app.vector-store.password:}") String vectorPassword,
      @Value("${app.agent.service-key:}") String agentServiceKey,
      @Value("${app.agent.internal-key:}") String agentInternalKey,
      @Value("${app.agent.internal-identity-secret:}") String identitySecret,
      @Value("${app.agent.approval-secret:}") String approvalSecret) {
    if (!isProduction(environment)) return;
    requireSecret("JWT_SECRET", jwtSecret, 32);
    requireSecret("DB_PASSWORD", databasePassword, 16);
    requireSecret("MINIO_ACCESS_KEY", storageAccessKey, 8);
    requireSecret("MINIO_SECRET_KEY", storageSecretKey, 16);
    requireSecret("VECTOR_DB_PASSWORD", vectorPassword, 16);
    requireSecret("AGENT_SERVICE_KEY", agentServiceKey, 24);
    requireSecret("AGENT_INTERNAL_KEY", agentInternalKey, 24);
    requireSecret("AGENT_INTERNAL_IDENTITY_SECRET", identitySecret, 32);
    requireSecret("AGENT_APPROVAL_SECRET", approvalSecret, 32);
    if (identitySecret.equals(approvalSecret) || identitySecret.equals(jwtSecret)) {
      throw new IllegalStateException(
          "Production identity, approval and JWT secrets must be distinct");
    }
  }

  private boolean isProduction(String environment) {
    return "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
  }

  private void requireSecret(String name, String value, int minLength) {
    if (!StringUtils.hasText(value)
        || value.length() < minLength
        || DEMO_VALUES.contains(value)
        || value.toLowerCase().contains("change-this")
        || value.toLowerCase().contains("replace-with")) {
      throw new IllegalStateException(
          name + " must be a unique non-demo secret in production (minimum " + minLength + " characters)");
    }
  }
}
