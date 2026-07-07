package com.internship.docpilot.service;

import com.internship.docpilot.dto.ChatRequest;
import com.internship.docpilot.model.SearchHit;
import java.util.List;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatService {
  private final KnowledgeBaseService kb;
  private final RetrievalService retrieval;
  private final AnswerRouterService ai;
  private final InferenceGuard guard;
  private final ConversationService conversations;
  private final Executor executor;
  private final JdbcTemplate jdbc;

  public ChatService(
      KnowledgeBaseService kb,
      RetrievalService r,
      AnswerRouterService ai,
      InferenceGuard g,
      ConversationService conversations,
      @Qualifier("chatExecutor") Executor e,
      JdbcTemplate j) {
    this.kb = kb;
    retrieval = r;
    this.ai = ai;
    guard = g;
    this.conversations = conversations;
    executor = e;
    jdbc = j;
  }

  public SseEmitter chat(Long uid, String role, ChatRequest request) {
    kb.requireRead(request.getKbId(), uid, role);
    Long conversationId = conversations.ensure(
        uid, role, request.getKbId(), request.getConversationId());
    InferenceGuard.Permit permit = guard.acquire(uid);
    conversations.addUserMessage(conversationId, uid, request.getQuestion());
    SseEmitter emitter = new SseEmitter(120000L);
    executor.execute(() -> run(uid, conversationId, request, permit, emitter));
    return emitter;
  }

  private void run(
      Long uid,
      Long conversationId,
      ChatRequest request,
      InferenceGuard.Permit permit,
      SseEmitter emitter) {
    long start = System.currentTimeMillis();
    int sourceCount = 0;
    try {
      emitter.send(SseEmitter.event().name("conversation").data(conversationId));
      emitter.send(SseEmitter.event().name("status").data("正在检索知识库"));
      List<SearchHit> hits = retrieval.search(request.getKbId(), request.getQuestion(), 4);
      sourceCount = hits.size();
      String answer = ai.answer(request.getQuestion(), hits);
      conversations.addAssistantMessage(conversationId, uid, answer, hits);
      emitter.send(SseEmitter.event().name("sources").data(hits));
      for (int i = 0; i < answer.length(); i += 18) {
        int end = Math.min(answer.length(), i + 18);
        emitter.send(SseEmitter.event().name("token").data(answer.substring(i, end)));
        Thread.sleep(25);
      }
      emitter.send(SseEmitter.event().name("done").data("完成"));
      emitter.complete();
    } catch (Exception e) {
      try {
        emitter.send(
            SseEmitter.event()
                .name("error")
                .data(e.getMessage() == null ? "问答失败" : e.getMessage()));
      } catch (Exception ignored) {
      }
      emitter.completeWithError(e);
    } finally {
      guard.release(permit);
      jdbc.update(
          "INSERT INTO chat_audit(user_id,kb_id,question,source_count,duration_ms) VALUES(?,?,?,?,?)",
          uid,
          request.getKbId(),
          request.getQuestion(),
          sourceCount,
          System.currentTimeMillis() - start);
    }
  }
}
