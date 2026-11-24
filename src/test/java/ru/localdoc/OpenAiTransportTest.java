package ru.localdoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import ru.localdoc.config.AppConfig;
import ru.localdoc.domain.*;
import ru.localdoc.llm.OpenAiTransport;
import ru.localdoc.llm.ChatTransport;
import ru.localdoc.processing.WorkUnit;
import ru.localdoc.validation.LlmValidator;
import ru.localdoc.validation.ValidationResult;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiTransportTest {
    HttpServer server;ExecutorService serverExecutor;
    AtomicInteger calls=new AtomicInteger();AtomicReference<String> path=new AtomicReference<>(),body=new AtomicReference<>();
    volatile Handler handler;
    @FunctionalInterface interface Handler { void accept(HttpExchange exchange) throws Exception; }
    @BeforeEach void startServer() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        serverExecutor=Executors.newCachedThreadPool(r -> { Thread t=new Thread(r);t.setDaemon(true);return t; });server.setExecutor(serverExecutor);
        handler=e -> respond(e,200,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Исправленный текст\",\"reasoning_content\":\"Скрытое рассуждение\"},\"finish_reason\":\"stop\"}]}");
        server.createContext("/",e -> {
            calls.incrementAndGet();path.set(e.getRequestURI().getPath());body.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try { handler.accept(e); } catch (Exception ignored) { e.close(); }
        });server.start();
    }
    @AfterEach void stopServer() { server.stop(0);serverExecutor.shutdownNow(); }
    AppConfig config(Duration timeout) {
        var c=TestSupport.config(AppConfig.VerificationMode.BOTH,3);
        var model=new AppConfig.Model(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1"),"generator",8192,2048,.2);
        var checker=new AppConfig.Model(model.baseUrl(),"checker",8192,256,0);
        return new AppConfig(c.file(),model,checker,c.verificationMode(),c.maxRegenerationRetries(),c.checkerProtocolRetries(),c.chunking(),c.contextParagraphs(),c.tokenSafetyFactor(),c.maxFileBytes(),Duration.ofSeconds(1),timeout,c.inputEncoding(),c.outputEncoding(),c.actionsDirectory());
    }
    static void respond(HttpExchange e,int status,String text) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");e.sendResponseHeaders(status,bytes.length);
        try (var out=e.getResponseBody()) { out.write(bytes); }
    }
    @Test void sendsOpenAiRequestAndUsesOnlyAssistantContent() throws Exception {
        var config=config(Duration.ofSeconds(3));
        try (var transport=new OpenAiTransport(config)) {
            var answer=transport.complete(config.generator(),"Системный промпт","Исходный текст",new CancellationToken());
            assertEquals("Исправленный текст",answer.text());assertEquals("stop",answer.finishReason());assertEquals("/v1/chat/completions",path.get());
            var request=new ObjectMapper().readTree(body.get());assertEquals("generator",request.path("model").asText());assertFalse(request.path("stream").asBoolean());
            assertEquals("Системный промпт",request.path("messages").get(0).path("content").asText());assertEquals(2048,request.path("max_tokens").asInt());
            assertFalse(request.has("response_format"),"Generator must still return ordinary document text");
        }
    }
    @Test void checkerRequestsJsonModeInsteadOfReceivingMarkdownEnvelope() throws Exception {
        // Reproduce Gemma's observed behavior: plain requests get fenced JSON;
        // requests using response_format get a bare JSON verdict.
        handler=e -> {
            var mapper=new ObjectMapper();var request=mapper.readTree(body.get());
            String verdict="{\"valid\":true,\"reason\":\"Смысл сохранён\"}";
            if (!"json_object".equals(request.path("response_format").path("type").asText())) verdict="```json\n"+verdict+"\n```";
            var envelope=mapper.createObjectNode();
            envelope.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("role","assistant").put("content",verdict);
            respond(e,200,envelope.toString());
        };
        var config=config(Duration.ofSeconds(2));
        try (var transport=new OpenAiTransport(config)) {
            var verdict=new LlmValidator(config,transport).validate(TestSupport.action("correct_errors"),new WorkUnit(0,0,6,"Текст.",""),new ChatTransport.Candidate("Текст.","stop"),java.util.List.of(),new CancellationToken());
            assertEquals(ValidationResult.Status.ACCEPT,verdict.status());assertEquals(1,calls.get());
            var request=new ObjectMapper().readTree(body.get());
            assertEquals("checker",request.path("model").asText());
            assertEquals("json_object",request.path("response_format").path("type").asText());
        }
    }
    @Test void jsonModeDoesNotBypassVerdictSchemaValidation() {
        handler=e -> {
            var envelope=new ObjectMapper().createObjectNode();
            envelope.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("role","assistant").put("content","{\"valid\":\"true\",\"reason\":\"ОК\"}");
            respond(e,200,envelope.toString());
        };
        var config=config(Duration.ofSeconds(2));
        try (var transport=new OpenAiTransport(config)) {
            var verdict=new LlmValidator(config,transport).validate(TestSupport.action("correct_errors"),new WorkUnit(0,0,6,"Текст.",""),new ChatTransport.Candidate("Текст.","stop"),java.util.List.of(),new CancellationToken());
            assertEquals(ValidationResult.Status.ERROR,verdict.status());
            assertEquals("CHECKER_PROTOCOL_ERROR",verdict.code());assertEquals(2,calls.get());
            assertTrue(verdict.reason().contains("valid типа boolean"));
        }
    }
    @Test void noAutomaticTransportRetries() {
        handler=e -> respond(e,503,"{\"error\":\"unavailable\"}");var config=config(Duration.ofSeconds(2));
        try (var transport=new OpenAiTransport(config)) {
            assertEquals("LLM_HTTP",assertThrows(AppException.class,() -> transport.complete(config.generator(),"system","text",new CancellationToken())).code());
            assertEquals(1,calls.get());
        }
    }
    @Test void invalidServerReplyIsExplicitError() {
        var config=config(Duration.ofSeconds(2));
        try (var transport=new OpenAiTransport(config)) {
            for (String invalid:new String[]{"null","not JSON","{\"choices\":[]}","{\"choices\":[{\"message\":{\"role\":\"user\",\"content\":\"text\"}}]}"}) {
                handler=e -> respond(e,200,invalid);
                assertEquals("LLM_PROTOCOL",assertThrows(AppException.class,() -> transport.complete(config.generator(),"system","text",new CancellationToken())).code());
            }
        }
    }
    @Test void timeoutIsBounded() {
        handler=e -> { Thread.sleep(1500);respond(e,200,"{}"); };var config=config(Duration.ofMillis(150));
        try (var transport=new OpenAiTransport(config)) {
            long started=System.nanoTime();
            assertEquals("LLM_TIMEOUT",assertThrows(AppException.class,() -> transport.complete(config.generator(),"system","text",new CancellationToken())).code());
            assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<1400);
        }
    }
    @Test void cancellationInterruptsPendingRequest() throws Exception {
        CountDownLatch received=new CountDownLatch(1);handler=e -> { received.countDown();Thread.sleep(3000);respond(e,200,"{}"); };
        var config=config(Duration.ofSeconds(10));var token=new CancellationToken();
        try (var transport=new OpenAiTransport(config);var worker=Executors.newSingleThreadExecutor()) {
            var future=worker.submit(() -> transport.complete(config.generator(),"system","text",token));
            assertTrue(received.await(2,TimeUnit.SECONDS));token.cancel();
            var ex=assertThrows(ExecutionException.class,() -> future.get(2,TimeUnit.SECONDS));assertInstanceOf(CancellationException.class,ex.getCause());
        }
    }
    @Test void modelDiscoveryUsesSeparateEndpoint() {
        handler=e -> respond(e,200,"{\"data\":[{\"id\":\"generator\"},{\"id\":\"checker\"}]}");var config=config(Duration.ofSeconds(2));
        try (var transport=new OpenAiTransport(config)) { assertEquals(java.util.List.of("generator","checker"),transport.models(config.generator(),new CancellationToken()));assertEquals("/v1/models",path.get()); }
    }
    @Test void limitsResponseBodySize() {
        handler=e -> respond(e,200," ".repeat(9*1024*1024));var config=config(Duration.ofSeconds(5));
        try (var transport=new OpenAiTransport(config)) { assertThrows(AppException.class,() -> transport.complete(config.generator(),"system","text",new CancellationToken())); }
    }
}
