package ru.localdoc.processing;

import ru.localdoc.config.*;
import ru.localdoc.domain.*;
import ru.localdoc.llm.ChatTransport;
import ru.localdoc.validation.*;
import java.util.*;
import java.util.function.Consumer;

public final class ProcessingService {
    public record Progress(UUID jobId,int unit,int total,String stage,int retriesUsed) { }
    public record Result(DocumentModel document,int retriesUsed,int unitCount) { }
    private final ChatTransport transport;
    public ProcessingService(ChatTransport transport) { this.transport=transport; }

    public Result process(UUID jobId,SourceDocument source,ActionDefinition action,AppConfig config,CancellationToken token,Consumer<Progress> progress) {
        return process(jobId,source,action,config,token,progress,null);
    }
    public Result revise(UUID jobId,SourceDocument source,ActionDefinition action,Result previous,String feedback,AppConfig config,CancellationToken token,Consumer<Progress> progress) {
        token.check();
        String previousText=previous.document().text();
        if (action.mode()==ActionDefinition.Mode.APPEND) {
            String original=source.document().text();
            String prefix=original+(original.endsWith("\n")?"":"\n\n");
            if (!previousText.startsWith(prefix)) throw new AppException("REVISION_INPUT","Предыдущий результат не соответствует исходному документу.");
            previousText=previousText.substring(prefix.length());
        }
        return process(jobId,source,action,config,token,progress,new RevisionContext(previousText,feedback));
    }
    private Result process(UUID jobId,SourceDocument source,ActionDefinition action,AppConfig config,CancellationToken token,Consumer<Progress> progress,RevisionContext revision) {
        progress.accept(new Progress(jobId,0,0,"Планирование",0));
        var planner=new ProcessingPlanner();
        var plan=revision==null?planner.plan(source.document(),action,config,token):planner.planRevision(source.document(),action,revision,config,token);
        int retries=0;
        var accepted=new LinkedHashMap<Integer,String>();
        var form=new FormValidator();
        var checker=new LlmValidator(config,transport);
        for (int index=0;index<plan.size();index++) {
            WorkUnit unit=plan.get(index); String rejection=null;
            while (true) {
                token.check();
                progress.accept(new Progress(jobId,index+1,plan.size(),"Генерация",retries));
                String request=PromptFactory.generation(action,unit,rejection,revision);
                String system=PromptFactory.generationSystem(action,revision);
                if (ProcessingPlanner.estimate(system+request,config)+config.generator().outputTokens()>config.generator().contextTokens()) throw ProcessingPlanner.contextError();
                var candidate=transport.complete(config.generator(),system,request,token);
                token.check();
                progress.accept(new Progress(jobId,index+1,plan.size(),"Проверка формы",retries));
                var verdict=form.validate(action,unit,candidate,token);
                if (verdict.status()==ValidationResult.Status.ERROR) throw new AppException(verdict.code(),verdict.reason());
                if (verdict.status()!=ValidationResult.Status.REJECT && config.verificationMode()==AppConfig.VerificationMode.BOTH) {
                    progress.accept(new Progress(jobId,index+1,plan.size(),"Проверка второй LLM",retries));
                    verdict=checker.validate(action,unit,candidate,verdict.warnings(),token,revision);
                } else if (verdict.status()!=ValidationResult.Status.REJECT && !verdict.warnings().isEmpty()) {
                    verdict=ValidationResult.reject("SEMANTIC_CHECK_REQUIRED","Сильное изменение текста требует проверки второй моделью. Включите режим BOTH.");
                }
                token.check();
                if (verdict.status()==ValidationResult.Status.ERROR) throw new AppException(verdict.code(),verdict.reason());
                if (verdict.status()==ValidationResult.Status.ACCEPT) { accepted.put(unit.id(),candidate.text());break; }
                if (retries>=config.maxRegenerationRetries()) throw new AppException("RETRY_LIMIT","Не удалось получить корректный ответ. Использовано повторов: " + retries + " из " + config.maxRegenerationRetries() + ". Фрагмент " + (index+1) + "/" + plan.size() + ". Последняя причина: " + verdict.reason());
                retries++; rejection=verdict.code()+": "+verdict.reason();
            }
        }
        token.check(); progress.accept(new Progress(jobId,plan.size(),plan.size(),"Сборка результата",retries));
        DocumentModel result=assemble(source.document(),action,plan,accepted);
        token.check();
        return new Result(result,retries,plan.size());
    }
    public static DocumentModel assemble(DocumentModel source,ActionDefinition action,List<WorkUnit> plan,Map<Integer,String> results) {
        if (results.size()!=plan.size()) throw new AppException("ASSEMBLY","Не все фрагменты прошли проверку.");
        if (action.mode()==ActionDefinition.Mode.APPEND) {
            if (plan.size()!=1 || !results.containsKey(plan.getFirst().id())) throw new AppException("ASSEMBLY","Некорректный план продолжения.");
            String original=source.text();
            return DocumentModel.fromText(original+(original.endsWith("\n")?"":"\n\n")+results.get(plan.getFirst().id()));
        }
        String original=source.text(); StringBuilder text=new StringBuilder();int cursor=0;
        Set<Integer> seen=new HashSet<>();
        for (var unit:plan) {
            if (unit.start()<cursor || unit.end()<unit.start() || unit.end()>original.length() || !seen.add(unit.id()) || !results.containsKey(unit.id()) || !original.substring(unit.start(),unit.end()).equals(unit.target()) || !original.substring(cursor,unit.start()).isBlank())
                throw new AppException("ASSEMBLY","Нарушены порядок или полнота фрагментов.");
            text.append(original,cursor,unit.start()).append(results.get(unit.id()));cursor=unit.end();
        }
        if (!original.substring(cursor).isBlank()) throw new AppException("ASSEMBLY","В плане пропущен исходный текст.");
        text.append(original,cursor,original.length());
        return DocumentModel.fromText(text.toString());
    }
}
