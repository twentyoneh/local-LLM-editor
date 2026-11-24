package ru.localdoc.llm;

import com.fasterxml.jackson.databind.*;
import ru.localdoc.config.AppConfig;
import ru.localdoc.domain.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public final class OpenAiTransport implements ChatTransport {
    private final HttpClient http;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public OpenAiTransport(AppConfig config) {
        http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        timeout = config.requestTimeout();
    }
    @Override public Candidate complete(AppConfig.Model model, String system, String user, CancellationToken token) {
        return complete(model,system,user,token,false);
    }
    @Override public Candidate completeJson(AppConfig.Model model, String system, String user, CancellationToken token) {
        return complete(model,system,user,token,true);
    }
    private Candidate complete(AppConfig.Model model, String system, String user, CancellationToken token, boolean jsonOutput) {
        token.check();
        try {
            var body=mapper.createObjectNode(); body.put("model",model.name()); body.put("stream",false);
            body.put("temperature",model.temperature()); body.put("max_tokens",model.outputTokens());
            if (jsonOutput) body.putObject("response_format").put("type","json_object");
            var messages=body.putArray("messages");
            messages.addObject().put("role","system").put("content",system);
            messages.addObject().put("role","user").put("content",user);
            URI uri=URI.create(model.baseUrl().toString()+"/chat/completions");
            var request=HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response=send(request,token);
            var root=mapper.readTree(response);
            if (root==null) throw new AppException("LLM_PROTOCOL","Сервер вернул пустой JSON.");
            var choice=root.path("choices").path(0);
            var message=choice.path("message");
            if (!"assistant".equals(message.path("role").asText()) || !(message.path("content").isTextual() || message.path("content").isNull()))
                throw new AppException("LLM_PROTOCOL","Сервер вернул ответ без текстового сообщения assistant.");
            return new Candidate(message.path("content").asText(""),choice.path("finish_reason").asText(""));
        } catch (IOException ex) { throw new AppException("LLM_PROTOCOL","Сервер вернул некорректный JSON.",ex); }
    }
    public List<String> models(AppConfig.Model model, CancellationToken token) {
        try {
            var request=HttpRequest.newBuilder(URI.create(model.baseUrl()+"/models")).timeout(timeout).GET().build();
            var root=mapper.readTree(send(request,token));
            if (!root.path("data").isArray()) throw new AppException("LLM_PROTOCOL","Сервер не вернул список моделей в формате OpenAI.");
            List<String> models=new ArrayList<>();
            for (var item:root.path("data")) if (item.path("id").isTextual()) models.add(item.path("id").asText());
            return List.copyOf(models);
        } catch (IOException ex) { throw new AppException("LLM_PROTOCOL","Не удалось прочитать список моделей.",ex); }
    }
    private byte[] send(HttpRequest request, CancellationToken token) {
        token.check();
        CompletableFuture<HttpResponse<byte[]>> future=http.sendAsync(request,info -> new LimitedSubscriber(8*1024*1024));
        token.attach(future);
        try {
            var response=future.get(timeout.toMillis()+1000,TimeUnit.MILLISECONDS);
            token.check();
            if (response.statusCode()<200 || response.statusCode()>=300)
                throw new AppException("LLM_HTTP","Локальный сервер вернул HTTP " + response.statusCode() + ". Проверьте URL, имя модели и её доступность.");
            return response.body();
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); future.cancel(true); throw new CancellationException("Задача отменена."); }
        catch (TimeoutException ex) { future.cancel(true); throw new AppException("LLM_TIMEOUT","Истекло время ожидания локальной модели.",ex); }
        catch (ExecutionException ex) {
            token.check();
            String code=ex.getCause() instanceof HttpTimeoutException ? "LLM_TIMEOUT" : "LLM_UNAVAILABLE";
            throw new AppException(code,"Не удалось получить ответ локальной модели. Проверьте сервер, URL и тайм-аут.",ex.getCause());
        } finally { token.detach(future); }
    }
    @Override public void close() { http.shutdownNow(); }
    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private final long limit; private long size; private Flow.Subscription subscription; private boolean failed;
        LimitedSubscriber(long limit) { this.limit=limit; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; delegate.onSubscribe(subscription); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (failed) return;
            for (var buffer:chunks) size+=buffer.remaining();
            if (size>limit) { failed=true; subscription.cancel(); delegate.onError(new IOException("Ответ сервера превышает 8 МБ")); }
            else delegate.onNext(chunks);
        }
        @Override public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
