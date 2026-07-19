package com.internship.docpilot.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {
  public int estimate(String text) {
    if (text == null || text.isEmpty()) return 0;
    int tokens = 0;
    boolean inLatin = false;
    int punctuation = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (isCjk(ch)) {
        tokens++;
        inLatin = false;
      } else if (Character.isLetterOrDigit(ch)) {
        if (!inLatin) tokens++;
        inLatin = true;
      } else {
        inLatin = false;
        if (!Character.isWhitespace(ch)) punctuation++;
      }
    }
    return Math.max(1, tokens + (punctuation + 3) / 4);
  }

  public List<String> terms(String text) {
    return new ArrayList<String>(new LinkedHashSet<String>(rawTerms(text)));
  }

  public Map<String, Integer> termFrequencies(String text) {
    Map<String, Integer> result = new LinkedHashMap<String, Integer>();
    for (String term : rawTerms(text)) {
      result.put(term, result.getOrDefault(term, 0) + 1);
    }
    return result;
  }

  private List<String> rawTerms(String text) {
    String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
    List<String> result = new ArrayList<String>();
    StringBuilder latin = new StringBuilder();
    List<Character> cjkRun = new ArrayList<Character>();
    for (int i = 0; i <= normalized.length(); i++) {
      char ch = i < normalized.length() ? normalized.charAt(i) : ' ';
      if (isCjk(ch)) {
        flushLatin(latin, result);
        cjkRun.add(ch);
      } else {
        flushCjk(cjkRun, result);
        if (Character.isLetterOrDigit(ch) || ch == '_') latin.append(ch);
        else flushLatin(latin, result);
      }
    }
    return new ArrayList<String>(result);
  }

  private void flushLatin(StringBuilder value, java.util.Collection<String> output) {
    if (value.length() > 1 && value.length() <= 64) output.add(value.toString());
    value.setLength(0);
  }

  private void flushCjk(List<Character> run, java.util.Collection<String> output) {
    if (run.size() == 1) {
      output.add(String.valueOf(run.get(0)));
    } else {
      for (int i = 0; i + 1 < run.size(); i++) {
        output.add("" + run.get(i) + run.get(i + 1));
      }
    }
    run.clear();
  }

  private boolean isCjk(char ch) {
    Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
  }
}
