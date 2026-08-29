package world.willfrog.agentlangchain.execution;

import dev.langchain4j.service.UserMessage;

interface LangchainFinalAnswerAiService {

    @UserMessage("{{it}}")
    String answer(String userMessage);
}
