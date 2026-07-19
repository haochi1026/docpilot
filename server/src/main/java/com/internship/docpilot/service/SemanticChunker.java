package com.internship.docpilot.service;

import com.internship.docpilot.model.DocumentChunkDraft;
import com.internship.docpilot.model.PageText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SemanticChunker {
  private static final Pattern HEADING =
      Pattern.compile("^(?:第[一二三四五六七八九十百0-9]+[章节部分]|[0-9]+(?:\\.[0-9]+){0,3}[、. ]|[一二三四五六七八九十]+[、.]).{1,60}$");

  private final TokenEstimator tokens;
  private final int maxTokens;
  private final int overlapTokens;

  public SemanticChunker(
      TokenEstimator tokens,
      @Value("${app.chunking.max-tokens:480}") int maxTokens,
      @Value("${app.chunking.overlap-tokens:60}") int overlapTokens) {
    this.tokens = tokens;
    this.maxTokens = Math.max(80, maxTokens);
    this.overlapTokens = Math.max(0, Math.min(overlapTokens, this.maxTokens / 3));
  }

  public List<DocumentChunkDraft> chunk(List<PageText> pages) {
    List<Unit> units = new ArrayList<Unit>();
    for (PageText page : pages) {
      String heading = null;
      String normalized = normalize(page.getText());
      for (String raw : normalized.split("\\n\\s*\\n|(?m)(?<=。)\\s*\\n")) {
        String paragraph = raw.trim();
        if (paragraph.isEmpty()) continue;
        if (isHeading(paragraph)) heading = paragraph;
        for (String part : splitLong(paragraph)) {
          units.add(new Unit(page.getPageNo(), heading, part, tokens.estimate(part)));
        }
      }
    }

    List<DocumentChunkDraft> result = new ArrayList<DocumentChunkDraft>();
    List<Unit> current = new ArrayList<Unit>();
    int currentTokens = 0;
    for (Unit unit : units) {
      // A chunk must never cross a PDF page boundary; otherwise page citations lie.
      if (!current.isEmpty() && !Objects.equals(current.get(0).page, unit.page)) {
        result.add(toDraft(current));
        current = new ArrayList<Unit>();
        currentTokens = 0;
      }
      if (!current.isEmpty() && currentTokens + unit.tokens > maxTokens) {
        result.add(toDraft(current));
        current = overlap(current);
        currentTokens = sum(current);
      }
      current.add(unit);
      currentTokens += unit.tokens;
    }
    if (!current.isEmpty()) result.add(toDraft(current));
    return result;
  }

  private List<String> splitLong(String paragraph) {
    List<String> result = new ArrayList<String>();
    int start = 0;
    while (start < paragraph.length()) {
      int end = tokenBudgetEnd(paragraph, start, maxTokens);
      if (end < paragraph.length()) {
        int sentence = Math.max(paragraph.lastIndexOf('。', end), paragraph.lastIndexOf('；', end));
        if (sentence > start + (end - start) / 2) end = sentence + 1;
      }
      result.add(paragraph.substring(start, end).trim());
      if (end >= paragraph.length()) break;
      int overlapStart = tokenBudgetStart(paragraph, end, overlapTokens);
      start = Math.max(start + 1, overlapStart);
    }
    return result;
  }

  private int tokenBudgetEnd(String text, int start, int budget) {
    int end = Math.min(text.length(), start + Math.max(1, budget));
    while (end < text.length() && tokens.estimate(text.substring(start, end)) < budget) end++;
    while (end > start + 1 && tokens.estimate(text.substring(start, end)) > budget) end--;
    return Math.max(start + 1, end);
  }

  private int tokenBudgetStart(String text, int end, int budget) {
    if (budget <= 0) return end;
    int start = Math.max(0, end - budget * 3);
    while (start < end && tokens.estimate(text.substring(start, end)) > budget) start++;
    return start;
  }

  private List<Unit> overlap(List<Unit> current) {
    List<Unit> tail = new ArrayList<Unit>();
    int count = 0;
    for (int i = current.size() - 1; i >= 0 && count < overlapTokens; i--) {
      tail.add(0, current.get(i));
      count += current.get(i).tokens;
    }
    return tail;
  }

  private DocumentChunkDraft toDraft(List<Unit> units) {
    String heading = units.get(0).heading;
    Integer page = units.get(0).page;
    StringBuilder content = new StringBuilder();
    if (heading != null && !heading.equals(units.get(0).text)) content.append(heading).append('\n');
    for (Unit unit : units) {
      if (content.length() > 0) content.append("\n\n");
      content.append(unit.text);
    }
    String value = content.toString().trim();
    return new DocumentChunkDraft(page, heading, value, tokens.estimate(value));
  }

  private int sum(List<Unit> units) {
    int value = 0;
    for (Unit unit : units) value += unit.tokens;
    return value;
  }

  private boolean isHeading(String text) {
    return text.length() <= 80
        && (HEADING.matcher(text).matches() || text.endsWith("：") || text.endsWith(":"));
  }

  private String normalize(String value) {
    return value.replace('\u0000', ' ')
        .replaceAll("[ \\t]+", " ")
        .replaceAll("\\r\\n?", "\\n")
        .replaceAll("\\n{3,}", "\\n\\n")
        .trim();
  }

  private static class Unit {
    private final Integer page;
    private final String heading;
    private final String text;
    private final int tokens;

    Unit(Integer page, String heading, String text, int tokens) {
      this.page = page;
      this.heading = heading;
      this.text = text;
      this.tokens = tokens;
    }
  }
}
