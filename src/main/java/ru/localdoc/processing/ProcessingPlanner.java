package ru.localdoc.processing;

import ru.localdoc.config.*;
import ru.localdoc.domain.*;
import java.util.ArrayList;
import java.util.List;

public final class ProcessingPlanner {
    /** Revisions use the complete source and previous answer; neither is silently truncated. */
    public List<WorkUnit> planRevision(DocumentModel document,ActionDefinition action,RevisionContext revision,AppConfig config,CancellationToken token) {
        token.check();
        String source=document.text();
        if (document.isBlank()) throw new AppException("IMPORT_EMPTY","Нет текста для обработки.");
        WorkUnit unit=action.mode()==ActionDefinition.Mode.APPEND
            ?new WorkUnit(0,source.length(),source.length(),"",source)
            :new WorkUnit(0,0,source.length(),source,"");
        String reserve="я".repeat(512);
        long generation=estimate(PromptFactory.generationSystem(action,revision)+PromptFactory.generation(action,unit,reserve,revision),config)+config.generator().outputTokens();
        long expectedOutput=estimate(revision.previousResult(),config)-64;
        boolean fits=generation<=config.generator().contextTokens() && expectedOutput<=config.generator().outputTokens()*.8;
        if (config.verificationMode()==AppConfig.VerificationMode.BOTH) {
            long verification=estimate(PromptFactory.verificationSystem(action,revision)+PromptFactory.verification(action,unit,"",List.of(reserve),true,revision),config)
                +(long)Math.ceil(config.generator().outputTokens()*1.5)+config.checker().outputTokens();
            fits &= verification<=config.checker().contextTokens();
        }
        token.check();
        if (!fits) throw new AppException("CONTEXT_LIMIT","Доработка требует исходник, предыдущий ответ и замечание целиком. Они не помещаются в контекст или лимит ответа модели. Уменьшите документ или замечание либо увеличьте contextWindowTokens / maxOutputTokens в конфигурации. Предыдущий результат остаётся доступен.");
        return List.of(unit);
    }
    public List<WorkUnit> plan(DocumentModel document, ActionDefinition action, AppConfig config, CancellationToken token) {
        String source=document.text();
        if (document.isBlank()) throw new AppException("IMPORT_EMPTY","Нет текста для обработки.");
        if (action.mode()==ActionDefinition.Mode.APPEND) {
            int start=0;
            while (true) {
                token.check();
                var unit=new WorkUnit(0,source.length(),source.length(),"",source.substring(start));
                if (fits(unit,action,config)) return List.of(unit);
                int remaining=source.codePointCount(start,source.length());
                if (remaining<2) throw contextError();
                start=source.offsetByCodePoints(start,Math.max(1,remaining/2));
            }
        }
        var whole=new WorkUnit(0,0,source.length(),source,"");
        if (fits(whole,action,config)) return List.of(whole);
        if (!config.chunking()) throw contextError();
        List<WorkUnit> units=new ArrayList<>();
        int start=0;
        while (start<source.length()) {
            token.check();
            while (start<source.length() && Character.isWhitespace(source.codePointAt(start))) start+=Character.charCount(source.codePointAt(start));
            if (start==source.length()) break;
            String context=context(source,start,config.contextParagraphs());
            int cap=Math.max(1,(int)Math.floor(config.generator().outputTokens()*.8*2/config.tokenSafetyFactor()));
            int available=0,scan=start;
            while (scan<source.length() && available<cap) { scan+=Character.charCount(source.codePointAt(scan));available++; }
            int low=1,high=available,best=0;
            while (low<=high) {
                int n=low+(high-low)/2; int end=source.offsetByCodePoints(start,n);
                if (fits(new WorkUnit(units.size(),start,end,source.substring(start,end),context),action,config)) { best=n;low=n+1; }
                else high=n-1;
            }
            if (best==0) throw contextError();
            int end=source.offsetByCodePoints(start,best);
            if (end<source.length()) end=boundary(source,start,end);
            while (end>start && Character.isWhitespace(source.codePointBefore(end))) end-=Character.charCount(source.codePointBefore(end));
            if (end==start) throw contextError();
            units.add(new WorkUnit(units.size(),start,end,source.substring(start,end),context));
            start=end;
        }
        return List.copyOf(units);
    }
    private int boundary(String source,int start,int end) {
        int minimum=start+(end-start)/2;
        int newline=source.lastIndexOf('\n',end-1);
        if (newline>=minimum) return newline;
        for (int i=end-1;i>=minimum;i--) {
            char c=source.charAt(i);
            if ((c=='.'||c=='!'||c=='?') && i+1<source.length() && Character.isWhitespace(source.charAt(i+1))) return i+1;
        }
        for (int i=end-1;i>=minimum;i--) if (Character.isWhitespace(source.charAt(i))) return i;
        return end;
    }
    private String context(String source,int start,int paragraphs) {
        if (paragraphs==0 || start==0) return "";
        int pos=start,count=0,completed=0;boolean nonBlank=false;
        while (pos>0 && count<512*paragraphs) {
            int cp=source.codePointBefore(pos);
            if (cp=='\n' && nonBlank) { if (++completed>=paragraphs) break;nonBlank=false; }
            else if (!Character.isWhitespace(cp)) nonBlank=true;
            pos-=Character.charCount(cp);count++;
        }
        return source.substring(pos,start);
    }
    public boolean fits(WorkUnit unit,ActionDefinition action,AppConfig config) {
        if (action.mode()==ActionDefinition.Mode.REPLACE && estimate(unit.target(),config)-64>config.generator().outputTokens()*.8) return false;
        String reservedReason="я".repeat(512);
        long generation=estimate(PromptFactory.generationSystem(action)+PromptFactory.generation(action,unit,reservedReason),config)+config.generator().outputTokens();
        if (generation>config.generator().contextTokens()) return false;
        if (config.verificationMode()==AppConfig.VerificationMode.BOTH) {
            long verification=estimate(action.verificationPrompt()+PromptFactory.verification(action,unit,"",List.of("я".repeat(512)),true),config)
                +(long)Math.ceil(config.generator().outputTokens()*1.5)+config.checker().outputTokens();
            if (verification>config.checker().contextTokens()) return false;
        }
        return true;
    }
    public static long estimate(String text,AppConfig config) {
        // Conservative heuristic, not a model tokenizer. Actual context errors remain explicit errors.
        return (long)Math.ceil(text.codePointCount(0,text.length())/2.0*config.tokenSafetyFactor())+64;
    }
    public static AppException contextError() { return new AppException("CONTEXT_LIMIT","Текст или промпт не помещается в контекстное окно модели. Увеличьте окно, уменьшите лимит ответа или размер контекста."); }
}
