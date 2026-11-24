package ru.localdoc.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import ru.localdoc.config.*;
import ru.localdoc.document.DocumentService;
import ru.localdoc.domain.*;
import ru.localdoc.llm.OpenAiTransport;
import ru.localdoc.processing.ProcessingService;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class EditorView implements AutoCloseable {
    private enum State { EMPTY, LOADED, IMPORTING, PROCESSING, READY_TO_SAVE, SAVING, SAVED, FAILED, CANCELLED, CHECKING }
    private final Window owner;
    private final Path configPath;
    private final BorderPane root=new BorderPane();
    private final DocumentService documents=new DocumentService();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"localdoc-worker");t.setDaemon(true);return t; });
    private final Button open=new Button("Открыть документ…"),run=new Button("Обработать"),cancel=new Button("Отмена"),save=new Button("Сохранить как…"),settings=new Button("Настройки"),check=new Button("Проверить подключение");
    private final Button revise=new Button("Доработать результат…");
    private final ComboBox<ActionDefinition> action=new ComboBox<>();
    private final ComboBox<String> encoding=new ComboBox<>();
    private final TextArea sourceText=new TextArea(),resultText=new TextArea();
    private final Label filename=new Label("Выберите файл DOCX, ODT или TXT"),fileDetails=new Label("Исходный документ остаётся без изменений"),status=new Label("Готово к работе"),connection=new Label("Подключение не проверено"),sourceCount=new Label(""),resultCount=new Label("");
    private final Label mode=new Label();
    private final ProgressBar progress=new ProgressBar(0);
    private AppConfig config;
    private SourceDocument source;
    private ProcessingService.Result result;
    private State state=State.EMPTY;
    private State revisionPreviousState;
    private UUID activeJob;
    private CancellationToken cancellation;
    private Task<?> running;
    private Path lastSelected;
    private boolean suppressChanges;

    public EditorView(Window owner,Path configPath) {
        this.owner=owner;this.configPath=configPath.toAbsolutePath().normalize();
        build();
        try { reloadConfig(); }
        catch (RuntimeException ex) { status.setText("Требуется исправить конфигурацию");Platform.runLater(() -> showError(ex)); }
        refresh();
    }
    public Parent root() { return root; }
    public boolean isBusy() { return switch (state) { case IMPORTING,PROCESSING,SAVING,CHECKING -> true; default -> false; }; }
    public boolean hasResult() { return result!=null && (state==State.READY_TO_SAVE || state==State.SAVED); }
    private void build() {
        root.setPadding(new Insets(28));
        Label title=new Label("Локальный редактор");title.getStyleClass().add("title");
        Label subtitle=new Label("Обработка документов с помощью моделей на вашем устройстве");subtitle.getStyleClass().add("subtitle");
        VBox heading=new VBox(5,title,subtitle);HBox.setHgrow(heading,Priority.ALWAYS);
        HBox header=new HBox(16,heading,settings);header.setAlignment(Pos.CENTER_LEFT);
        open.setId("openButton");run.setId("runButton");cancel.setId("cancelButton");save.setId("saveButton");settings.setId("settingsButton");check.setId("checkButton");
        revise.setId("reviseButton");
        run.getStyleClass().add("primary");save.getStyleClass().add("primary");
        filename.getStyleClass().add("filename");filename.setWrapText(true);fileDetails.getStyleClass().add("muted");
        VBox fileInfo=new VBox(5,filename,fileDetails);HBox.setHgrow(fileInfo,Priority.ALWAYS);
        HBox fileRow=new HBox(20,fileInfo,open);fileRow.setAlignment(Pos.CENTER_LEFT);fileRow.getStyleClass().add("card");
        encoding.getItems().setAll("UTF-8","windows-1251","UTF-16LE","UTF-16BE","KOI8-R");encoding.setValue("UTF-8");encoding.setId("encodingChoice");
        action.setId("actionChoice");action.setMaxWidth(Double.MAX_VALUE);action.setPrefWidth(300);
        VBox actionGroup=new VBox(6,new Label("Действие"),action);HBox.setHgrow(actionGroup,Priority.ALWAYS);
        VBox encodingGroup=new VBox(6,new Label("Кодировка TXT"),encoding);
        HBox commands=new HBox(12,actionGroup,encodingGroup,run,cancel,save);commands.setAlignment(Pos.BOTTOM_LEFT);
        mode.getStyleClass().add("mode-label");
        VBox top=new VBox(24,header,fileRow,commands,mode);top.setPadding(new Insets(0,0,18,0));root.setTop(top);
        sourceText.setId("sourceText");resultText.setId("resultText");
        sourceText.setEditable(false);resultText.setEditable(false);sourceText.setWrapText(true);resultText.setWrapText(true);
        sourceText.setPromptText("Здесь появится текст открытого документа.");
        resultText.setPromptText("Результат появится после прохождения проверок.");
        VBox left=textPanel("Исходный текст",sourceCount,sourceText),right=textPanel("Проверенный результат",resultCount,resultText);
        HBox revisionRow=new HBox(revise);revisionRow.setAlignment(Pos.CENTER_RIGHT);right.getChildren().add(revisionRow);
        SplitPane split=new SplitPane(left,right);split.setDividerPositions(.5);root.setCenter(split);
        status.setId("statusLabel");status.setWrapText(true);status.getStyleClass().add("status");
        progress.setMaxWidth(Double.MAX_VALUE);progress.setPrefHeight(5);
        connection.getStyleClass().add("muted");connection.setWrapText(true);HBox.setHgrow(connection,Priority.ALWAYS);
        HBox connections=new HBox(15,connection,check);connections.setAlignment(Pos.CENTER_LEFT);
        VBox bottom=new VBox(10,status,progress,connections);bottom.setPadding(new Insets(20,0,0,0));root.setBottom(bottom);
        open.setOnAction(e -> chooseDocument());run.setOnAction(e -> startProcessing());cancel.setOnAction(e -> cancelProcessing());save.setOnAction(e -> chooseDestination());
        settings.setOnAction(e -> showSettings());check.setOnAction(e -> checkConnections());
        revise.setOnAction(e -> showRevisionDialog());
        action.valueProperty().addListener((o,old,value) -> {
            if (!suppressChanges && !isBusy()) { invalidateResult();state=source==null?State.EMPTY:State.LOADED;status.setText("Действие выбрано. Можно запустить обработку.");refresh(); }
        });
        encoding.valueProperty().addListener((o,old,value) -> {
            if (!suppressChanges && !isBusy() && lastSelected!=null && lastSelected.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt")) loadDocument(lastSelected);
        });
    }
    private VBox textPanel(String name,Label count,TextArea area) {
        Label label=new Label(name);label.getStyleClass().add("panel-title");count.getStyleClass().add("muted");
        Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        HBox heading=new HBox(8,label,spacer,count);heading.setAlignment(Pos.CENTER_LEFT);
        VBox panel=new VBox(10,heading,area);panel.setPadding(new Insets(12));VBox.setVgrow(area,Priority.ALWAYS);return panel;
    }
    private void reloadConfig() {
        AppConfig loaded=AppConfig.load(configPath);
        var loadedActions=new ActionRegistry().load(loaded.actionsDirectory());
        String previous=action.getValue()==null?"correct_errors":action.getValue().id();
        suppressChanges=true;
        try {
            config=loaded;action.getItems().setAll(loadedActions);
            action.setValue(loadedActions.stream().filter(a -> a.id().equals(previous)).findFirst().orElse(loadedActions.getFirst()));
            if (source==null) encoding.setValue(config.inputEncoding().name());
        } finally { suppressChanges=false; }
        mode.setText(config.verificationMode()==AppConfig.VerificationMode.BOTH?"Проверка: форма ответа + другая локальная LLM":"Проверка: только форма ответа · смысловая проверка отключена");
        connection.setText("Генератор: "+config.generator().name()+" · Проверяющая: "+(config.checker()==null?"отключена":config.checker().name())+" · подключение не проверено");
    }
    private void chooseDocument() {
        FileChooser chooser=new FileChooser();chooser.setTitle("Открыть документ");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Документы (*.docx, *.odt, *.txt)","*.docx","*.odt","*.txt","*.DOCX","*.ODT","*.TXT"));
        var selected=chooser.showOpenDialog(owner);if (selected!=null) loadDocument(selected.toPath());
    }
    public void loadDocument(Path path) {
        if (isBusy() || config==null) return;
        lastSelected=path;State previous=state;Charset charset=Charset.forName(encoding.getValue());
        AppConfig snapshot=config;
        submit(State.IMPORTING,"Чтение документа…",(id,token) -> documents.read(path,charset,snapshot.maxFileBytes()),loaded -> {
            if (!loaded.warnings().isEmpty() && !confirm("Текстовый импорт",String.join("\n",loaded.warnings())+"\n\nПродолжить с основным текстом и обычными абзацами?")) { state=previous;status.setText("Импорт отменён.");return; }
            source=loaded;sourceText.setText(source.document().text());sourceCount.setText(characters(source.document().text()));
            filename.setText(source.path().getFileName().toString());fileDetails.setText(source.format()+" · "+source.path().getParent());
            invalidateResult();state=State.LOADED;status.setText("Документ открыт. Выберите действие и запустите обработку.");
        },ex -> { state=previous;status.setText("Не удалось открыть документ.");showError(ex); });
    }
    public void selectAction(String id) {
        if (isBusy()) return;
        action.getItems().stream().filter(a -> a.id().equals(id)).findFirst().ifPresent(action::setValue);
    }
    public void startProcessing() {
        if (source==null || config==null || action.getValue()==null || isBusy()) return;
        invalidateResult();beginProcessing(null);
    }
    private void showRevisionDialog() {
        if (!hasResult() || isBusy()) return;
        Dialog<String> dialog=new Dialog<>();dialog.initOwner(owner);dialog.setTitle("Доработать результат");
        ButtonType apply=new ButtonType("Доработать",ButtonBar.ButtonData.OK_DONE);
        ButtonType dismiss=new ButtonType("Отмена",ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(apply,dismiss);
        Label description=new Label("Что нужно изменить в результате? Замечание получат генерирующая и проверяющая модели.");description.setWrapText(true);
        TextArea feedback=new TextArea();feedback.setId("revisionFeedback");feedback.setWrapText(true);feedback.setPrefRowCount(5);
        feedback.setPromptText("Например: сохрани названия разделов и разбей длинные предложения на короткие.");
        Label note=new Label(action.getValue().mode()==ActionDefinition.Mode.APPEND
            ?"Будет заменено только созданное продолжение. Исходный текст сохранится."
            :"Будет создан новый вариант результата с учётом исходного текста и вашего замечания.");note.setWrapText(true);
        VBox content=new VBox(12,description,feedback,note);content.setPadding(new Insets(12));content.setPrefWidth(540);
        dialog.getDialogPane().setContent(content);
        Button submit=(Button)dialog.getDialogPane().lookupButton(apply);submit.setId("revisionSubmitButton");
        submit.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> feedback.getText().isBlank(),feedback.textProperty()));
        dialog.setResultConverter(button -> button==apply?feedback.getText().strip():null);
        dialog.setOnShown(e -> feedback.requestFocus());
        dialog.showAndWait().ifPresent(this::startRevision);
    }
    public void startRevision(String feedback) {
        if (!hasResult() || isBusy() || config==null || action.getValue()==null) return;
        if (feedback==null || feedback.isBlank()) { showError(new AppException("REVISION_INPUT","Опишите, что нужно изменить в результате."));return; }
        beginProcessing(feedback.strip());
    }
    private void beginProcessing(String feedback) {
        SourceDocument sourceSnapshot=source;ActionDefinition selected=action.getValue();AppConfig snapshot=config;
        ProcessingService.Result previous=feedback==null?null:result;
        revisionPreviousState=previous==null?null:state;
        submit(State.PROCESSING,previous==null?"Подготовка обработки…":"Подготовка доработки по замечанию…",(id,token) -> {
            try (var transport=new OpenAiTransport(snapshot)) {
                Consumer<ProcessingService.Progress> report=event -> Platform.runLater(() -> {
                    if (!event.jobId().equals(activeJob) || state!=State.PROCESSING) return;
                    progress.setProgress(event.total()==0?-1:(event.unit()-1)/(double)event.total());
                    status.setText(event.stage()+" · фрагмент "+event.unit()+" / "+event.total()+" · повторы "+event.retriesUsed()+" / "+snapshot.maxRegenerationRetries());
                });
                var service=new ProcessingService(transport);
                return previous==null?service.process(id,sourceSnapshot,selected,snapshot,token,report)
                    :service.revise(id,sourceSnapshot,selected,previous,feedback,snapshot,token,report);
            }
        },processed -> {
            revisionPreviousState=null;
            result=processed;resultText.setText(result.document().text());resultCount.setText(characters(result.document().text()));
            state=State.READY_TO_SAVE;progress.setProgress(1);
            status.setText((previous==null?"Готово":"Доработка готова")+" · проверено фрагментов: "+result.unitCount()+" · повторов: "+result.retriesUsed()+" / "+snapshot.maxRegenerationRetries());
        },ex -> {
            state=revisionPreviousState==null?State.FAILED:revisionPreviousState;revisionPreviousState=null;progress.setProgress(0);
            status.setText(previous==null?"Обработка не завершена. Исходный документ сохранён.":"Доработка не завершена. Предыдущий результат доступен для сохранения и новой доработки.");showError(ex);
        });
    }
    public void cancelProcessing() {
        if (state!=State.PROCESSING) return;
        activeJob=null;if (cancellation!=null) cancellation.cancel();if (running!=null) running.cancel(true);
        running=null;restoreAfterCancellation();refresh();
    }
    private void restoreAfterCancellation() {
        progress.setProgress(0);
        if (revisionPreviousState!=null) {
            state=revisionPreviousState;revisionPreviousState=null;
            status.setText("Доработка отменена. Предыдущий результат остаётся доступен.");
        } else {
            state=State.CANCELLED;invalidateResult();status.setText("Обработка отменена. Можно запустить заново.");
        }
    }
    private void chooseDestination() {
        if (!hasResult()) return;
        FileChooser chooser=new FileChooser();chooser.setTitle("Сохранить обработанный документ");
        Map<FileChooser.ExtensionFilter,DocumentFormat> formats=new LinkedHashMap<>();
        for (var f:DocumentFormat.values()) {
            var filter=new FileChooser.ExtensionFilter(f.name()+" (*."+f.extension()+")","*."+f.extension());
            formats.put(filter,f);chooser.getExtensionFilters().add(filter);if (f==source.format()) chooser.setSelectedExtensionFilter(filter);
        }
        String name=source.path().getFileName().toString();name=name.substring(0,name.lastIndexOf('.'))+"_результат."+source.format().extension();
        chooser.setInitialFileName(name);if (Files.isDirectory(source.path().getParent())) chooser.setInitialDirectory(source.path().getParent().toFile());
        var selected=chooser.showSaveDialog(owner);if (selected==null) return;
        DocumentFormat format=formats.get(chooser.getSelectedExtensionFilter());
        String file=selected.getName();int dot=file.lastIndexOf('.');
        String suffix=dot>=0?file.substring(dot+1):"";
        if (dot>=0 && Arrays.stream(DocumentFormat.values()).anyMatch(f -> suffix.equalsIgnoreCase(f.extension()))) file=file.substring(0,dot);
        Path target=selected.toPath().resolveSibling(file+"."+format.extension());
        boolean exists=Files.exists(target,LinkOption.NOFOLLOW_LINKS);
        if (exists && !confirm("Заменить файл?","Файл уже существует:\n"+target+"\n\nЗаменить его обработанным документом?")) return;
        saveTo(target,format,exists);
    }
    public void saveTo(Path destination,DocumentFormat format,boolean allowOverwrite) {
        if (!hasResult() || isBusy()) return;
        State previous=state;SourceDocument original=source;DocumentModel processed=result.document();Charset charset=config.outputEncoding();
        submit(State.SAVING,"Сохранение и проверка файла…",(id,token) -> {
            boolean changed=documents.sourceChanged(original);
            Path saved=documents.write(original,processed,destination,format,charset,allowOverwrite);
            return new Saved(saved,changed);
        },saved -> {
            state=State.SAVED;status.setText("Сохранено: "+saved.path());
            if (saved.sourceChanged()) showInfo("Источник изменился","Исходный файл изменился после открытия. Сохранён результат обработки ранее прочитанного снимка.");
        },ex -> { state=previous;status.setText("Не удалось сохранить файл. Проверенный результат остаётся доступен.");showError(ex); });
    }
    private record Saved(Path path,boolean sourceChanged) { }
    private void checkConnections() {
        if (config==null || isBusy()) return;
        State previous=state;AppConfig snapshot=config;
        submit(State.CHECKING,"Проверка локальных серверов…",(id,token) -> {
            try (var transport=new OpenAiTransport(snapshot)) {
                var generator=transport.models(snapshot.generator(),token);
                String message=generator.contains(snapshot.generator().name())?"Генератор доступен":"Генератор не найден; доступны: "+String.join(", ",generator);
                if (snapshot.checker()!=null) {
                    var checker=transport.models(snapshot.checker(),token);
                    message+=checker.contains(snapshot.checker().name())?" · Проверяющая модель доступна":" · Проверяющая модель не найдена; доступны: "+String.join(", ",checker);
                }
                return message;
            }
        },message -> { state=previous;connection.setText(message);status.setText("Проверка подключения завершена."); },ex -> { state=previous;connection.setText("Проверка подключения не пройдена");status.setText("Не удалось проверить подключение.");showError(ex); });
    }
    private void showSettings() {
        if (isBusy()) return;
        Properties properties=new Properties();
        try (var in=Files.newBufferedReader(configPath,StandardCharsets.UTF_8)) { properties.load(in); }
        catch (IOException ex) { showError(new AppException("CONFIG","Не удалось прочитать "+configPath,ex));return; }
        Dialog<ButtonType> dialog=new Dialog<>();dialog.initOwner(owner);dialog.setTitle("Настройки локальных моделей");
        var apply=new ButtonType("Сохранить",ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(apply,ButtonType.CANCEL);
        GridPane grid=new GridPane();grid.setHgap(15);grid.setVgap(12);grid.setPadding(new Insets(16));
        Map<String,TextField> fields=new LinkedHashMap<>();
        String[][] descriptions={{"generation.baseUrl","URL генератора"},{"generation.model","Модель генератора"},{"verification.baseUrl","URL проверяющей LLM"},{"verification.model","Проверяющая модель"}};
        int row=0;for (var entry:descriptions) {
            TextField field=new TextField(properties.getProperty(entry[0],""));field.setPrefColumnCount(30);fields.put(entry[0],field);
            grid.add(new Label(entry[1]),0,row);grid.add(field,1,row++);
        }
        ComboBox<String> verification=new ComboBox<>();verification.getItems().setAll("BOTH","FORM_ONLY");verification.setValue(properties.getProperty("verification.mode","BOTH"));
        grid.add(new Label("Режим проверки"),0,row);grid.add(verification,1,row++);
        int initialRetries=3;
        try { initialRetries=Math.clamp(Integer.parseInt(properties.getProperty("processing.maxRegenerationRetries","3")),0,20); } catch (NumberFormatException ignored) { }
        Spinner<Integer> retries=new Spinner<>(0,20,initialRetries);retries.setEditable(false);
        grid.add(new Label("Повторов на документ"),0,row);grid.add(retries,1,row++);
        Label note=new Label("BOTH: форма + другая локальная модель. FORM_ONLY: ограниченная проверка формы.\nОстальные параметры задаются в файле:\n"+configPath);note.setWrapText(true);note.setMaxWidth(620);grid.add(note,0,row,2,1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().lookupButton(apply).addEventFilter(javafx.event.ActionEvent.ACTION,event -> {
            fields.forEach((key,field) -> properties.setProperty(key,field.getText().trim()));
            properties.setProperty("verification.mode",verification.getValue());properties.setProperty("processing.maxRegenerationRetries",retries.getValue().toString());
            Path temp=null;
            try {
                temp=Files.createTempFile(configPath.getParent(),"config-",".properties");
                try (var out=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)) { properties.store(out,"Local document editor"); }
                AppConfig candidate=AppConfig.load(temp);new ActionRegistry().load(candidate.actionsDirectory());
                Files.move(temp,configPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                reloadConfig();invalidateResult();state=source==null?State.EMPTY:State.LOADED;status.setText("Настройки сохранены. Для нового результата запустите обработку заново.");refresh();
            } catch (Exception ex) { event.consume();showError(ex); }
            finally { if (temp!=null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
        });
        dialog.showAndWait();
    }
    @FunctionalInterface private interface Operation<T> { T run(UUID id,CancellationToken token) throws Exception; }
    private <T> void submit(State busyState,String message,Operation<T> operation,Consumer<T> success,Consumer<Throwable> failure) {
        UUID id=UUID.randomUUID();activeJob=id;cancellation=new CancellationToken();CancellationToken token=cancellation;
        Task<T> task=new Task<>() { @Override protected T call() throws Exception { token.check();return operation.run(id,token); } };
        running=task;state=busyState;status.setText(message);progress.setProgress(-1);refresh();
        task.setOnSucceeded(e -> {
            if (!id.equals(activeJob)) return;running=null;activeJob=null;progress.setProgress(0);
            try { success.accept(task.getValue()); } catch (RuntimeException ex) { failure.accept(ex); }
            refresh();
        });
        task.setOnFailed(e -> { if (!id.equals(activeJob)) return;running=null;activeJob=null;progress.setProgress(0);failure.accept(task.getException());refresh(); });
        task.setOnCancelled(e -> {
            if (!id.equals(activeJob)) return;running=null;activeJob=null;restoreAfterCancellation();refresh();
        });
        worker.submit(task);
    }
    private void invalidateResult() { result=null;resultText.clear();resultCount.setText(""); }
    private void refresh() {
        boolean busy=isBusy();open.setDisable(busy || config==null);action.setDisable(busy || config==null);encoding.setDisable(busy || config==null);
        run.setDisable(busy || config==null || source==null || action.getValue()==null);cancel.setDisable(state!=State.PROCESSING);
        save.setDisable(busy || !hasResult());revise.setDisable(busy || !hasResult());settings.setDisable(busy);check.setDisable(busy || config==null);
    }
    private String characters(String text) { return String.format(Locale.forLanguageTag("ru"),"%,d зн.",text.codePointCount(0,text.length())); }
    private boolean confirm(String title,String text) {
        Alert alert=new Alert(Alert.AlertType.CONFIRMATION,text,ButtonType.YES,ButtonType.NO);alert.initOwner(owner);alert.setTitle(title);alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.NO)==ButtonType.YES;
    }
    private void showError(Throwable error) {
        String message=error instanceof AppException app?app.getMessage()+"\n\nКод: "+app.code():"Не удалось выполнить действие: "+Objects.toString(error.getMessage(),error.getClass().getSimpleName());
        Alert alert=new Alert(Alert.AlertType.ERROR);alert.initOwner(owner);alert.setTitle("Не удалось выполнить действие");alert.setHeaderText(null);alert.setContentText(message);alert.getDialogPane().setMinWidth(560);alert.show();
    }
    private void showInfo(String title,String text) { Alert alert=new Alert(Alert.AlertType.INFORMATION,text,ButtonType.OK);alert.initOwner(owner);alert.setTitle(title);alert.setHeaderText(null);alert.show(); }
    public boolean requestClose() { return !isBusy() || confirm("Закрыть приложение?","Текущая задача будет прервана. Закрыть приложение?"); }
    @Override public void close() { if (cancellation!=null) cancellation.cancel();if (running!=null) running.cancel(true);worker.shutdownNow(); }
}
