package com.internship.docpilot.service;

import com.internship.docpilot.model.SearchHit;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface AiAnswerService {
  String answer(String question, List<SearchHit> hits) throws Exception;

  default String answer(
      String question,
      List<SearchHit> hits,
      List<Map<String, String>> history,
      Consumer<String> tokenConsumer)
      throws Exception {
    String answer = answer(question, hits);
    if (tokenConsumer != null && answer != null && !answer.isEmpty()) tokenConsumer.accept(answer);
    return answer;
  }
}
