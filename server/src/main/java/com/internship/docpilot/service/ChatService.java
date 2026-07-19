package com.internship.docpilot.service;

import com.internship.docpilot.dto.AgentResumeRequest;
import com.internship.docpilot.dto.ChatRequest;
import com.internship.docpilot.model.SearchHit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatService {
  private final KnowledgeBaseService knowledgeBases;
  private final RetrievalService retrieval;
  private final AnswerRouterService ai;
  private final AgentClient agent;
  private final boolean fallbackToRag;
  private final InferenceGuard guard;
  private final ConversationService conversations;
  private final Executor executor;
  private final JdbcTemplate jdbc;
  private final AgentApprovalService approvals;

  public ChatService(
      KnowledgeBaseService knowledgeBases,
      RetrievalService retrieval,
      AnswerRouterService ai,
      AgentClient agent,
      @Value("${app.agent.fallback-to-rag:true}") boolean fallbackToRag,
      InferenceGuard guard,
      ConversationService conversations,
      @Qualifier("chatExecutor") Executor executor,
      JdbcTemplate jdbc,
      AgentApprovalService approvals) {
    this.knowledgeBases = knowledgeBases;
    this.retrieval = retrieval;
    this.ai = ai;
    this.agent = agent;
    this.fallbackToRag = fallbackToRag;
    this.guard = guard;
    this.conversations = conversations;
    this.executor = executor;
    this.jdbc = jdbc;
    this.approvals = approvals;
  }

  public SseEmitter chat(Long uid, String username, String role, ChatRequest request) {
    knowledgeBases.requireRead(request.getKbId(), uid, role);
    Long conversationId =
        conversations.ensure(uid, role, request.getKbId(), request.getConversationId());
    InferenceGuard.Permit permit = guard.acquire(uid);
    List<Map<String, String>> history = conversations.recentModelMessages(conversationId, uid, 6);
    conversations.addUserMessage(conversationId, uid, request.getQuestion());
    SseEmitter emitter = new SseEmitter(180000L);
    try {
      executor.execute(
          () ->
              runInitial(
                  uid, username, role, conversationId, request, history, permit, emitter));
    } catch (RuntimeException e) {
      guard.release(permit);
      throw e;
    }
    return emitter;
  }

  public SseEmitter resume(
      Long uid, String username, String role, AgentResumeRequest request) {
    knowledgeBases.requireRead(request.getKbId(), uid, role);
    Long conversationId =
        conversations.ensure(uid, role, request.getKbId(), request.getConversationId());
    AgentApprovalService.Decision approval =
        approvals.decide(uid, conversationId, request.getApprovalId(), request.getDecision());
    InferenceGuard.Permit permit = guard.acquire(uid);
    SseEmitter emitter = new SseEmitter(180000L);
    try {
      executor.execute(
          () ->
              runResume(
                  uid, username, role, conversationId, request, approval, permit, emitter));
    } catch (RuntimeException e) {
      guard.release(permit);
      throw e;
    }
    return emitter;
  }

  private void runInitial(
      Long uid,
      String username,
      String role,
      Long conversationId,
      ChatRequest request,
      List<Map<String, String>> history,
      InferenceGuard.Permit permit,
      SseEmitter emitter) {
    long start = System.currentTimeMillis();
    int sourceCount = 0;
    try {
      emitter.send(SseEmitter.event().name("conversation").data(conversationId));
      if (agent.isEnabled()) {
        try {
          AgentClient.RunResult result =
              agent.stream(
                  uid,
                  username,
                  role,
                  request.getKbId(),
                  conversationId,
                  request.getQuestion(),
                  null,
                  null,
                  null,
                  history,
                  emitter);
          sourceCount = result.getSources().size();
          if (result.isInterrupted()) {
            Map<String, Object> approval =
                approvals.create(
                    uid,
                    conversationId,
                    request.getKbId(),
                    "docpilot-" + conversationId,
                    result.getApproval());
            emitter.send(SseEmitter.event().name("approval").data(approval));
            emitter.complete();
            return;
          }
          persistAgentAnswer(conversationId, uid, result, emitter);
          return;
        } catch (Exception agentFailure) {
          if (!fallbackToRag) throw agentFailure;
          emitter.send(
              SseEmitter.event().name("status").data("Agent 暂不可用，已降级为固定 RAG 流程"));
          emitter.send(SseEmitter.event().name("replace").data(""));
        }
      }
      sourceCount = runLegacy(uid, conversationId, request, history, emitter);
    } catch (Exception e) {
      fail(emitter, e);
    } finally {
      guard.release(permit);
      audit(uid, request.getKbId(), request.getQuestion(), sourceCount, start);
    }
  }

  private void runResume(
      Long uid,
      String username,
      String role,
      Long conversationId,
      AgentResumeRequest request,
      AgentApprovalService.Decision approval,
      InferenceGuard.Permit permit,
      SseEmitter emitter) {
    long start = System.currentTimeMillis();
    int sourceCount = 0;
    try {
      if (!agent.isEnabled()) {
        throw new IllegalStateException("当前未启用 Agent，无法恢复审批任务");
      }
      AgentClient.RunResult result =
          agent.stream(
              uid,
              username,
              role,
              request.getKbId(),
              conversationId,
              null,
              request.getDecision(),
              approval.getApprovalId(),
              approval.getToken(),
              Collections.<Map<String, String>>emptyList(),
              emitter);
      sourceCount = result.getSources().size();
      if (result.isInterrupted()) {
        Map<String, Object> nextApproval =
            approvals.create(
                uid,
                conversationId,
                request.getKbId(),
                "docpilot-" + conversationId,
                result.getApproval());
        emitter.send(SseEmitter.event().name("approval").data(nextApproval));
        emitter.complete();
        return;
      }
      if ("reject".equals(request.getDecision())) {
        approvals.markRejectedResumed(uid, conversationId, approval.getApprovalId());
      }
      persistAgentAnswer(conversationId, uid, result, emitter);
    } catch (Exception e) {
      fail(emitter, e);
    } finally {
      guard.release(permit);
      audit(
          uid,
          request.getKbId(),
          "[HITL] " + request.getDecision(),
          sourceCount,
          start);
    }
  }

  public Map<String, Object> pendingApproval(
      Long uid, String role, Long conversationId) {
    conversations.requireOwnedView(conversationId, uid, role);
    Map<String, Object> pending = approvals.pending(uid, conversationId);
    return pending == null ? Collections.<String, Object>emptyMap() : pending;
  }

  private void persistAgentAnswer(
      Long conversationId, Long uid, AgentClient.RunResult result, SseEmitter emitter)
      throws Exception {
    conversations.addAssistantMessage(
        conversationId, uid, result.getAnswer(), result.getSources());
    emitter.send(SseEmitter.event().name("done").data("完成"));
    emitter.complete();
  }

  private int runLegacy(
      Long uid,
      Long conversationId,
      ChatRequest request,
      List<Map<String, String>> history,
      SseEmitter emitter)
      throws Exception {
    emitter.send(SseEmitter.event().name("status").data("正在检索知识库"));
    List<SearchHit> hits = retrieval.search(request.getKbId(), request.getQuestion(), 4);
    emitter.send(SseEmitter.event().name("sources").data(hits));
    emitter.send(SseEmitter.event().name("status").data("正在生成回答"));
    String answer =
        ai.answer(
            request.getQuestion(),
            hits,
            history,
            token -> {
              try {
                emitter.send(SseEmitter.event().name("token").data(token));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    conversations.addAssistantMessage(conversationId, uid, answer, hits);
    emitter.send(SseEmitter.event().name("done").data("完成"));
    emitter.complete();
    return hits.size();
  }

  private void fail(SseEmitter emitter, Exception error) {
    try {
      emitter.send(
          SseEmitter.event()
              .name("error")
              .data(error.getMessage() == null ? "问答失败" : error.getMessage()));
    } catch (Exception ignored) {
    }
    emitter.completeWithError(error);
  }

  private void audit(
      Long uid, Long kbId, String question, int sourceCount, long startedAt) {
    jdbc.update(
        "INSERT INTO chat_audit(user_id,kb_id,question,source_count,duration_ms) VALUES(?,?,?,?,?)",
        uid,
        kbId,
        question,
        sourceCount,
        System.currentTimeMillis() - startedAt);
  }
}
