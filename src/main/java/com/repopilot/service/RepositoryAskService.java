package com.repopilot.service;

import com.repopilot.service.GeminiAnswerService.Answer;
import org.springframework.stereotype.Service;

@Service
public class RepositoryAskService {

    private final RepositoryIndexService indexService;
    private final GeminiAnswerService answerService;

    public RepositoryAskService(RepositoryIndexService indexService, GeminiAnswerService answerService) {
        this.indexService = indexService;
        this.answerService = answerService;
    }

    public Answer ask(String question, String issueDescription) {
        return answerService.answer(question, issueDescription, indexService.search(question));
    }
}
