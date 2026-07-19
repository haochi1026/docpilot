package com.internship.docpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.internship.docpilot.model.DocumentChunkDraft;
import com.internship.docpilot.model.PageText;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticChunkerTest {
  @Test
  void tokenEstimatorBuildsChineseBigramsAndLatinTerms() {
    TokenEstimator estimator = new TokenEstimator();
    List<String> terms = estimator.terms("知识库 Agent retrieval");
    assertTrue(terms.contains("知识"));
    assertTrue(terms.contains("agent"));
    assertTrue(terms.contains("retrieval"));
    assertTrue(estimator.estimate("知识库 Agent") >= 4);
  }

  @Test
  void chunksByPageHeadingParagraphAndTokenBudget() {
    TokenEstimator estimator = new TokenEstimator();
    SemanticChunker chunker = new SemanticChunker(estimator, 80, 12);
    String paragraph =
        "出差申请必须在出发前提交并由直属负责人审批，报销时应附审批记录和有效票据。";
    StringBuilder longPage = new StringBuilder("第一章：差旅审批\n\n");
    for (int i = 0; i < 10; i++) longPage.append(paragraph).append("\n\n");

    List<DocumentChunkDraft> chunks =
        chunker.chunk(
            Arrays.asList(
                new PageText(1, longPage.toString(), false),
                new PageText(2, "第二章：住宿标准\n\n住宿标准按城市等级执行。", false)));

    assertTrue(chunks.size() >= 2);
    assertEquals(Integer.valueOf(1), chunks.get(0).getPageNo());
    assertFalse(chunks.get(0).getHeading().isEmpty());
    assertTrue(chunks.stream().anyMatch(item -> Integer.valueOf(2).equals(item.getPageNo())));
    assertTrue(chunks.stream().allMatch(item -> item.getTokenCount() > 0));
  }
}
