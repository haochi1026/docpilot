package com.internship.docpilot.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
  private final SecretKey key;
  private final long expireMillis;

  public JwtService(
      @Value("${app.jwt-secret}") String secret, @Value("${app.jwt-expire-hours:24}") long hours) {
    key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    expireMillis = hours * 3600_000L;
  }

  public String create(Long id, String username, String role) {
    Map<String, Object> claims = new HashMap<String, Object>();
    claims.put("uid", id);
    claims.put("role", role);
    Date now = new Date();
    return Jwts.builder()
        .setClaims(claims)
        .setSubject(username)
        .setIssuedAt(now)
        .setExpiration(new Date(now.getTime() + expireMillis))
        .signWith(key)
        .compact();
  }

  public Claims parse(String token) {
    return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
  }
}
