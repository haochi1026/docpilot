package com.internship.docpilot.service;

import com.internship.docpilot.model.SearchHit;
import java.util.List;

public interface AiAnswerService {
  String answer(String question, List<SearchHit> hits) throws Exception;
}
