package com.internship.docpilot.service;

import com.internship.docpilot.model.SearchHit;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class AnswerRouterService implements AiAnswerService {
  private final ModelSettingsService settings;
  private final LocalAnswerService local;
  private final OpenAiCompatibleAnswerService remote;

  public AnswerRouterService(ModelSettingsService settings, LocalAnswerService local, OpenAiCompatibleAnswerService remote) {
    this.settings = settings;
    this.local = local;
    this.remote = remote;
  }

  public String answer(String question, List<SearchHit> hits) throws Exception {
    return settings.current().aiEnabled() ? remote.answer(question, hits) : local.answer(question, hits);
  }

  public String answer(
      String question,
      List<SearchHit> hits,
      List<Map<String, String>> history,
      Consumer<String> tokenConsumer)
      throws Exception {
    return settings.current().aiEnabled()
        ? remote.answer(question, hits, history, tokenConsumer)
        : local.answer(question, hits, history, tokenConsumer);
  }
}
