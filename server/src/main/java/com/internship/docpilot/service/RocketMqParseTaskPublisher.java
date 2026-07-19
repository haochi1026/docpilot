package com.internship.docpilot.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.queue.mode", havingValue = "rocketmq")
public class RocketMqParseTaskPublisher implements ParseTaskPublisher {
  private final String nameserver;
  private final String topic;
  private final DocumentParseService parser;
  private DefaultMQProducer producer;
  private DefaultMQPushConsumer consumer;

  public RocketMqParseTaskPublisher(
      @Value("${app.queue.rocketmq-nameserver}") String nameserver,
      @Value("${app.queue.topic}") String topic,
      DocumentParseService parser) {
    this.nameserver = nameserver;
    this.topic = topic;
    this.parser = parser;
  }

  @PostConstruct
  public void start() throws Exception {
    producer = new DefaultMQProducer("docpilot-producer");
    producer.setNamesrvAddr(nameserver);
    producer.start();

    consumer = new DefaultMQPushConsumer("docpilot-parser");
    consumer.setNamesrvAddr(nameserver);
    consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    consumer.subscribe(topic, "*");
    consumer.registerMessageListener(
        new MessageListenerConcurrently() {
          @Override
          public ConsumeConcurrentlyStatus consumeMessage(
              List<MessageExt> messages, ConsumeConcurrentlyContext context) {
            try {
              for (MessageExt message : messages) {
                parser.parse(Long.valueOf(new String(message.getBody(), StandardCharsets.UTF_8)));
              }
              return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception ex) {
              return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
          }
        });
    consumer.start();
  }

  @Override
  public void publish(Long documentId) throws Exception {
    producer.send(
        new Message(topic, "PARSE", String.valueOf(documentId).getBytes(StandardCharsets.UTF_8)));
  }

  @PreDestroy
  public void stop() {
    if (consumer != null) consumer.shutdown();
    if (producer != null) producer.shutdown();
  }
}
