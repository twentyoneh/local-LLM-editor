package ru.localdoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.localdoc.config.*;
import ru.localdoc.domain.*;
import ru.localdoc.llm.ChatTransport;
import ru.localdoc.processing.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingServiceTest {
    static final class Fake implements ChatTransport {
        int generatorCalls,checkerCalls;
        List<String> requests=new ArrayList<>();
        BiFunction<Integer,String,Candidate> generator=(n,user) -> new Candidate(target(user),"stop");
        BiFunction<Integer,String,Candidate> checker=(n,user) -> new Candidate("{\"valid\":true,\"reason\":\"Корректно\"}","stop");
        @Override public Candidate complete(AppConfig.Model model,String system,String user,CancellationToken token) {
            token.check();requests.add(user);
            return model.name().equals("generator")?generator.apply(++generatorCalls,user):checker.apply(++checkerCalls,user);
        }
        static String target(String user) { try { return new ObjectMapper().readTree(user).path("targetText").asText(); } catch (Exception ex) { throw new AssertionError(ex); } }
    }
    ProcessingService.Result process(Fake fake,String source,String action,AppConfig config) {
        return new ProcessingService(fake).process(UUID.randomUUID(),TestSupport.source(source),TestSupport.action(action),config,new CancellationToken(),p -> {});
    }
    @Test void acceptsUnchangedTextAndChecksWithSecondModel() {
        Fake fake=new Fake();String text="Текст без ошибок.";
        var result=process(fake,text,"correct_errors",TestSupport.config(AppConfig.VerificationMode.BOTH,3));
        assertEquals(text,result.document().text());assertEquals(1,fake.generatorCalls);assertEquals(1,fake.checkerCalls);assertEquals(0,result.retriesUsed());
    }
    @Test void retriesUseOriginalAndIncludeRejection() {
        Fake fake=new Fake();fake.generator=(n,user) -> new ChatTransport.Candidate(n==1?"Текст не содержит ошибок.":"Исправленный текст.","stop");
        var result=process(fake,"В тексте есть ашибка.","correct_errors",TestSupport.config(AppConfig.VerificationMode.BOTH,3));
        assertEquals(1,result.retriesUsed());assertEquals(2,fake.generatorCalls);assertEquals(1,fake.checkerCalls);
        assertEquals("В тексте есть ашибка.",Fake.target(fake.requests.get(1)));assertTrue(fake.requests.get(1).contains("previousRejection"));
    }
    @Test void defaultLimitMeansFourGenerationsAndZeroMeansOne() {
        for (int limit:new int[]{0,3}) {
            Fake fake=new Fake();fake.generator=(n,user) -> new ChatTransport.Candidate("Текст не содержит ошибок.","stop");
            assertEquals("RETRY_LIMIT",assertThrows(AppException.class,() -> process(fake,"Источник с ашибкой.","correct_errors",TestSupport.config(AppConfig.VerificationMode.BOTH,limit))).code());
            assertEquals(limit+1,fake.generatorCalls);assertEquals(0,fake.checkerCalls);
        }
    }
    @Test void checkerRejectionCausesRegeneration() {
        Fake fake=new Fake();fake.checker=(n,user) -> new ChatTransport.Candidate(n==1?"{\"valid\":false,\"reason\":\"Потерян смысл\"}":"{\"valid\":true,\"reason\":\"ОК\"}","stop");
        var result=process(fake,"Обычный текст.","correct_errors",TestSupport.config(AppConfig.VerificationMode.BOTH,3));
        assertEquals(1,result.retriesUsed());assertEquals(2,fake.generatorCalls);assertEquals(2,fake.checkerCalls);
    }
    @Test void malformedCheckerJsonOnlyRetriesChecker() {
        Fake fake=new Fake();fake.checker=(n,user) -> new ChatTransport.Candidate(n==1?"Конечно, текст верен.":"{\"valid\":true,\"reason\":\"ОК\"}","stop");
        var result=process(fake,"Текст.","correct_errors",TestSupport.config(AppConfig.VerificationMode.BOTH,3));
        assertEquals(0,result.retriesUsed());assertEquals(1,fake.generatorCalls);assertEquals(2,fake.checkerCalls);
    }
    @Test void invalidCheckerDoesNotBecomeApproval() {
        for (String invalid:List.of("{\"valid\":\"true\",\"reason\":\"OK\"}","{\"valid\":true}","{\"valid\":true,\"reason\":\"OK\"} trailing","{\"valid\":false,\"valid\":true,\"reason\":\"OK\"}","```json\n{\"valid\":true,\"reason\":\"OK\"}\n```")) {
            Fake fake=new Fake();fake.checker=(n,user) -> new ChatTransport.Candidate(invalid,"stop");
            assertEquals("CHECKER_PROTOCOL_ERROR",assertThrows(AppException.class,() -> process(fake,"Текст.","correct_errors",TestSupport.config(AppConfig.VerificationMode.BOTH,3))).code());
            assertEquals(1,fake.generatorCalls);assertEquals(2,fake.checkerCalls);
        }
    }
    @Test void truncatedCheckerVerdictIsNeverAcceptedEvenWhenJsonIsComplete() {
        for (String finish:List.of("length","max_tokens")) {
            Fake fake=new Fake();fake.checker=(n,user) -> new ChatTransport.Candidate("{\"valid\":true,\"reason\":\"ОК\"}",finish);
            var error=assertThrows(AppException.class,() -> process(fake,"Текст.","correct_errors",TestSupport.config(AppConfig.VerificationMode.BOTH,3)));
            assertEquals("CHECKER_PROTOCOL_ERROR",error.code());assertTrue(error.getMessage().contains("verification.maxOutputTokens"));
            assertEquals(1,fake.generatorCalls);assertEquals(2,fake.checkerCalls);
        }
    }
    @Test void transportFailureDoesNotRegenerateCandidate() {
        Fake fake=new Fake();fake.checker=(n,user) -> { throw new AppException("LLM_UNAVAILABLE","Недоступно"); };
        assertEquals("LLM_UNAVAILABLE",assertThrows(AppException.class,() -> process(fake,"Текст.","correct_errors",TestSupport.config(AppConfig.VerificationMode.BOTH,3))).code());
        assertEquals(1,fake.generatorCalls);assertEquals(1,fake.checkerCalls);
    }
    @Test void continuationPreservesWholeOriginalExactlyOnce() {
        Fake fake=new Fake();fake.generator=(n,user) -> new ChatTransport.Candidate("Новая глава.","stop");
        String source="Первая глава.\nКонец исходника.";
        var result=process(fake,source,"continue",TestSupport.config(AppConfig.VerificationMode.BOTH,3));
        assertEquals(source+"\n\nНовая глава.",result.document().text());
    }
    @Test void chunkedIdentityPreservesWhitespaceEmojiAndOrder() {
        Fake fake=new Fake();String text="  \n"+("Абзац 😀 с пробелами. ".repeat(15)+"\n\n").repeat(3)+"Конец  \n";
        var result=process(fake,text,"correct_errors",TestSupport.config(AppConfig.VerificationMode.FORM_ONLY,3,200));
        assertTrue(result.unitCount()>1);assertEquals(text,result.document().text());assertEquals(result.unitCount(),fake.generatorCalls);
    }
    @Test void retriesAreSharedAcrossChunks() {
        String source="Первый "+"а".repeat(90)+"\nВторой "+"б".repeat(90)+"\nТретий "+"в".repeat(90);
        AppConfig config=TestSupport.config(AppConfig.VerificationMode.FORM_ONLY,3,150);
        var plan=new ProcessingPlanner().plan(DocumentModel.fromText(source),TestSupport.action("correct_errors"),config,new CancellationToken());
        assertTrue(plan.size()>=2);
        Fake fake=new Fake();Map<String,Integer> calls=new HashMap<>();
        fake.generator=(n,user) -> {
            String target=Fake.target(user);int count=calls.merge(target,1,Integer::sum);
            int failures=target.equals(plan.getFirst().target())?2:target.equals(plan.get(1).target())?1:0;
            return new ChatTransport.Candidate(count<=failures?"Текст не содержит ошибок.":target,"stop");
        };
        var result=process(fake,source,"correct_errors",config);
        assertEquals(3,result.retriesUsed());assertEquals(plan.size()+3,fake.generatorCalls);assertEquals(source,result.document().text());
    }
    @Test void cancellationPreventsResultPublication() {
        Fake fake=new Fake();CancellationToken token=new CancellationToken();
        fake.generator=(n,user) -> { token.cancel();return new ChatTransport.Candidate(Fake.target(user),"stop"); };
        assertThrows(CancellationException.class,() -> new ProcessingService(fake).process(UUID.randomUUID(),TestSupport.source("Текст."),TestSupport.action("correct_errors"),TestSupport.config(AppConfig.VerificationMode.BOTH,3),token,p -> {}));
        assertEquals(0,fake.checkerCalls);
    }
    @Test void assemblerRefusesMissingSkippedOrReorderedFragments() {
        var source=DocumentModel.fromText("Первый\nВторой");var action=TestSupport.action("correct_errors");
        var first=new WorkUnit(0,0,6,"Первый","");var second=new WorkUnit(1,7,13,"Второй","");
        assertThrows(AppException.class,() -> ProcessingService.assemble(source,action,List.of(first,second),Map.of(0,"Первый")));
        assertThrows(AppException.class,() -> ProcessingService.assemble(source,action,List.of(second,first),Map.of(0,"Первый",1,"Второй")));
        assertThrows(AppException.class,() -> ProcessingService.assemble(source,action,List.of(second),Map.of(1,"Второй")));
    }
}
