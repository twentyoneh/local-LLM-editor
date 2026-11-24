package ru.localdoc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.localdoc.config.AppConfig;
import ru.localdoc.domain.*;
import ru.localdoc.llm.ChatTransport;
import ru.localdoc.processing.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class ManualRevisionTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String ORIGINAL="Я хачу пайти дамой.";
    private static final String PREVIOUS="Я хочу пойти домой.";
    private static final String FEEDBACK="Сохрани букву ё и проверь пунктуацию.";

    private ProcessingService.Result result(String text,int retries) {
        return new ProcessingService.Result(DocumentModel.fromText(text),retries,1);
    }
    private ProcessingService.Result revise(ProcessingServiceTest.Fake fake,String original,String previous,String action,String feedback,int retries) {
        return new ProcessingService(fake).revise(UUID.randomUUID(),TestSupport.source(original),TestSupport.action(action),result(previous,3),feedback,
            TestSupport.config(AppConfig.VerificationMode.BOTH,retries),new CancellationToken(),p -> {});
    }
    @Test void bothModelsReceiveSourcePreviousResultAndFeedback() throws Exception {
        var fake=new ProcessingServiceTest.Fake();
        fake.generator=(n,user) -> new ChatTransport.Candidate(PREVIOUS,"stop");
        var revised=revise(fake,ORIGINAL,PREVIOUS,"correct_errors",FEEDBACK,3);
        assertEquals(PREVIOUS,revised.document().text());assertEquals(0,revised.retriesUsed());
        assertEquals(1,fake.generatorCalls);assertEquals(1,fake.checkerCalls);
        for (String request:fake.requests) {
            JsonNode data=JSON.readTree(request);
            assertEquals(ORIGINAL,data.path("targetText").asText());
            assertEquals(PREVIOUS,data.path("previousResult").asText());
            assertEquals(FEEDBACK,data.path("userFeedback").asText());
        }
        var context=new RevisionContext(PREVIOUS,FEEDBACK);
        assertTrue(PromptFactory.generationSystem(TestSupport.action("correct_errors"),context).contains("userFeedback"));
        assertTrue(PromptFactory.verificationSystem(TestSupport.action("correct_errors"),context).contains("userFeedback"));
    }
    @Test void revisionRetainsFeedbackDuringBothRegenerationAndCheckerProtocolRetry() throws Exception {
        var fake=new ProcessingServiceTest.Fake();
        fake.generator=(n,user) -> new ChatTransport.Candidate(PREVIOUS,"stop");
        fake.checker=(n,user) -> new ChatTransport.Candidate(switch(n) {
            case 1 -> "не JSON";
            case 2 -> "{\"valid\":false,\"reason\":\"Замечание не учтено\"}";
            default -> "{\"valid\":true,\"reason\":\"Учтено\"}";
        },"stop");
        var revised=revise(fake,ORIGINAL,PREVIOUS,"correct_errors",FEEDBACK,3);
        assertEquals(1,revised.retriesUsed());assertEquals(2,fake.generatorCalls);assertEquals(3,fake.checkerCalls);
        for (String request:fake.requests) {
            JsonNode data=JSON.readTree(request);
            assertEquals(FEEDBACK,data.path("userFeedback").asText());assertEquals(PREVIOUS,data.path("previousResult").asText());
        }
        assertTrue(JSON.readTree(fake.requests.get(2)).path("protocolError").isTextual());
        assertTrue(JSON.readTree(fake.requests.get(3)).path("previousRejection").asText().contains("Замечание не учтено"));
    }
    @Test void formRejectionSkipsCheckerAndRevisionGetsFreshRetryBudget() {
        var fake=new ProcessingServiceTest.Fake();
        fake.generator=(n,user) -> new ChatTransport.Candidate(n<=3?"Текст не содержит ошибок.":PREVIOUS,"stop");
        var revised=revise(fake,ORIGINAL,PREVIOUS,"correct_errors",FEEDBACK,3);
        assertEquals(3,revised.retriesUsed());assertEquals(4,fake.generatorCalls);assertEquals(1,fake.checkerCalls);
    }
    @Test void exhaustedRevisionDoesNotMutatePreviousResult() {
        var fake=new ProcessingServiceTest.Fake();var previous=result(PREVIOUS,3);
        fake.generator=(n,user) -> new ChatTransport.Candidate("Текст не содержит ошибок.","stop");
        var error=assertThrows(AppException.class,() -> new ProcessingService(fake).revise(UUID.randomUUID(),TestSupport.source(ORIGINAL),TestSupport.action("correct_errors"),previous,FEEDBACK,
            TestSupport.config(AppConfig.VerificationMode.BOTH,3),new CancellationToken(),p -> {}));
        assertEquals("RETRY_LIMIT",error.code());assertEquals(4,fake.generatorCalls);assertEquals(0,fake.checkerCalls);
        assertEquals(PREVIOUS,previous.document().text());assertEquals(3,previous.retriesUsed());
    }
    @Test void continuationRevisionReplacesOnlyGeneratedPartOnEveryRevision() throws Exception {
        for (String original:List.of("Начало.","Начало.\n")) {
            var fake=new ProcessingServiceTest.Fake();
            String prefix=original+(original.endsWith("\n")?"":"\n\n");
            fake.generator=(n,user) -> new ChatTransport.Candidate("Новое продолжение.","stop");
            var first=revise(fake,original,prefix+"Старое продолжение.","continue","Измени продолжение.",3);
            assertEquals(prefix+"Новое продолжение.",first.document().text());
            for (String request:fake.requests) {
                JsonNode data=JSON.readTree(request);
                assertEquals("",data.path("targetText").asText());assertEquals(original,data.path("readOnlyContext").asText());
                assertEquals("Старое продолжение.",data.path("previousResult").asText());
            }
            fake.requests.clear();fake.generator=(n,user) -> new ChatTransport.Candidate("Ещё один вариант.","stop");
            var second=revise(fake,original,first.document().text(),"continue","Уточни концовку.",3);
            assertEquals(prefix+"Ещё один вариант.",second.document().text());
            assertEquals("Новое продолжение.",JSON.readTree(fake.requests.getFirst()).path("previousResult").asText());
        }
    }
    @Test void invalidOrBlankInputNeverReachesModels() {
        var fake=new ProcessingServiceTest.Fake();
        for (String feedback:List.of(""," \n\t")) {
            assertEquals("REVISION_INPUT",assertThrows(AppException.class,() -> revise(fake,ORIGINAL,PREVIOUS,"correct_errors",feedback,3)).code());
        }
        assertEquals("REVISION_INPUT",assertThrows(AppException.class,() -> revise(fake,"Начало.","Другой документ.","continue",FEEDBACK,3)).code());
        assertEquals(0,fake.generatorCalls);assertEquals(0,fake.checkerCalls);
    }
    @Test void oversizedSourcePreviousAnswerOrFeedbackIsNotSilentlyTruncated() {
        for (String field:List.of("source","previous","feedback")) {
            var fake=new ProcessingServiceTest.Fake();String huge="Длинный текст 😀 ".repeat(3000);
            var error=assertThrows(AppException.class,() -> revise(fake,field.equals("source")?huge:ORIGINAL,field.equals("previous")?huge:PREVIOUS,"correct_errors",field.equals("feedback")?huge:FEEDBACK,3));
            assertEquals("CONTEXT_LIMIT",error.code());assertEquals(0,fake.generatorCalls);assertEquals(0,fake.checkerCalls);
        }
    }
    @Test void cancellationPreventsPublishingARevisionAndCheckingIt() {
        var fake=new ProcessingServiceTest.Fake();var token=new CancellationToken();var previous=result(PREVIOUS,3);
        fake.generator=(n,user) -> { token.cancel();return new ChatTransport.Candidate(PREVIOUS,"stop"); };
        assertThrows(CancellationException.class,() -> new ProcessingService(fake).revise(UUID.randomUUID(),TestSupport.source(ORIGINAL),TestSupport.action("correct_errors"),previous,FEEDBACK,
            TestSupport.config(AppConfig.VerificationMode.BOTH,3),token,p -> {}));
        assertEquals(0,fake.checkerCalls);assertEquals(PREVIOUS,previous.document().text());
    }
    @Test void formOnlyRevisionDoesNotCallChecker() {
        var fake=new ProcessingServiceTest.Fake();fake.generator=(n,user) -> new ChatTransport.Candidate(PREVIOUS,"stop");
        var revised=new ProcessingService(fake).revise(UUID.randomUUID(),TestSupport.source(ORIGINAL),TestSupport.action("correct_errors"),result(PREVIOUS,0),FEEDBACK,
            TestSupport.config(AppConfig.VerificationMode.FORM_ONLY,3),new CancellationToken(),p -> {});
        assertEquals(PREVIOUS,revised.document().text());assertEquals(1,fake.generatorCalls);assertEquals(0,fake.checkerCalls);
    }
}
