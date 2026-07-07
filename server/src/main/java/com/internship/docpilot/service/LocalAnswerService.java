package com.internship.docpilot.service;

import com.internship.docpilot.model.SearchHit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class LocalAnswerService implements AiAnswerService {
  public String answer(String question, List<SearchHit> hits) {
    if (hits.isEmpty()) return "当前知识库没有命中可用于回答的文档片段。";
    Set<String> questionTerms = grams(question);
    List<Sentence> candidates = new ArrayList<Sentence>();
    Set<String> seen = new HashSet<String>();
    for (int source = 0; source < hits.size(); source++) {
      SearchHit hit = hits.get(source);
      for (String raw : hit.getContent().split("(?<=[。！？!?])|\\n+")) {
        String sentence = raw.replaceAll("\\s+", " ").trim();
        if (sentence.length() < 10 || !seen.add(sentence)) continue;
        Set<String> terms = grams(sentence);
        int overlap = 0;
        for (String term : questionTerms) if (terms.contains(term)) overlap++;
        double relevance =
            (questionTerms.isEmpty() ? 0 : (double) overlap / questionTerms.size())
                + hit.getScore() * 0.35;
        candidates.add(new Sentence(sentence, source + 1, relevance));
      }
    }
    Collections.sort(candidates, Comparator.comparingDouble(Sentence::getScore).reversed());
    if (candidates.isEmpty() || candidates.get(0).score < 0.03) {
      return "资料中没有找到与“" + question + "”直接相关的内容。建议换用更具体的关键词。";
    }
    StringBuilder answer = new StringBuilder();
    answer.append("针对“").append(question).append("”，资料中可直接确认：\n\n");
    int count = Math.min(4, candidates.size());
    for (int i = 0; i < count; i++) {
      Sentence item = candidates.get(i);
      answer
          .append(i + 1)
          .append(". ")
          .append(item.content)
          .append(" [来源")
          .append(item.source)
          .append("]\n\n");
    }
    answer.append("当前为本地抽取式回答，不会进行模型推理；启用大模型后可进一步归纳和组织语言。");
    return answer.toString();
  }

  private Set<String> grams(String text) {
    String normalized = text == null ? "" : text.toLowerCase().replaceAll("\\s+", "");
    Set<String> result = new HashSet<String>();
    for (int i = 0; i < normalized.length(); i++) {
      result.add(String.valueOf(normalized.charAt(i)));
      if (i + 1 < normalized.length()) result.add(normalized.substring(i, i + 2));
    }
    return result;
  }

  private static class Sentence {
    private final String content;
    private final int source;
    private final double score;

    Sentence(String content, int source, double score) {
      this.content = content;
      this.source = source;
      this.score = score;
    }

    double getScore() {
      return score;
    }
  }
}
