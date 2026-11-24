package ru.localdoc;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ru.localdoc.ui.EditorView;
import java.nio.file.Path;

public final class Main extends Application {
    private EditorView editor;
    @Override public void start(Stage stage) {
        var args=getParameters().getNamed();
        Path config=Path.of(args.getOrDefault("config",System.getProperty("localdoc.config","config/config.properties")));
        editor=new EditorView(stage,config);
        Scene scene=new Scene(editor.root(),1180,820);
        scene.getStylesheets().add(Main.class.getResource("/editor.css").toExternalForm());
        stage.setTitle("Локальный редактор документов");
        stage.setMinWidth(900);stage.setMinHeight(680);stage.setScene(scene);
        stage.setOnCloseRequest(event -> { if (!editor.requestClose()) event.consume(); });
        stage.show();
        if (args.containsKey("open")) editor.loadDocument(Path.of(args.get("open")));
    }
    @Override public void stop() { if (editor!=null) editor.close(); }
    public static void main(String[] args) { launch(args); }
}
