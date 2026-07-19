package com.internship.docpilot.config;

import com.internship.docpilot.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {
  public OutboxMetrics(MeterRegistry registry, OutboxRepository outbox) {
    Gauge.builder("docpilot.outbox.open", outbox, value -> value.pendingCount())
        .description("Pending, retrying or leased DocPilot outbox messages")
        .register(registry);
    Gauge.builder("docpilot.outbox.dead", outbox, value -> value.deadCount())
        .description("DocPilot outbox messages in the dead-letter state")
        .register(registry);
    Gauge.builder(
            "docpilot.outbox.oldest.open.age.seconds",
            outbox,
            value -> value.oldestOpenAgeSeconds())
        .description("Age of the oldest open DocPilot outbox message")
        .register(registry);
  }
}
