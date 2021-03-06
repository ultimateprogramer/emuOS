package emuos.ui;

import emuos.diskmanager.FilePath;
import emuos.os.Kernel;
import emuos.os.shell.Command;
import emuos.os.shell.CommandHistory;
import emuos.os.shell.Shell;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import static emuos.ui.MainWindow.WINDOW_TITLE;

/**
 * @author Link
 */
public class TerminalController implements Initializable {

    private final EmuInputStream eis = new EmuInputStream();
    private final EmuOutputStream eos = new EmuOutputStream();
    private Kernel kernel;
    @FXML
    private TextArea inputArea;
    private Stage stage;
    private Stage monitorStage;
    private Parent itsRoot;
    private int lastPromptPosition = 0;
    private Shell shell;
    private Thread shellThread;

    /**
     * Initializes the controller class.
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
    }

    private void clearInput() {
        if (lastPromptPosition != inputArea.getLength()) {
            inputArea.deleteText(lastPromptPosition, inputArea.getLength());
        }
    }

    private void print(String text) {
        inputArea.appendText(text);
    }

    void setStage(Stage stage) {
        this.stage = stage;
        stage.setOnCloseRequest(event -> handleAppClose());
        itsRoot = stage.getScene().getRoot();
    }

    private void handleAppClose() {
        if (monitorStage != null && monitorStage.isShowing()) {
            monitorStage.close();
        }
        if (shellThread.isAlive()) {
            shell.stop();
            shellThread.interrupt();
        }
        stage.close();
    }

    @FXML
    private void handleKeyPressed(KeyEvent keyEvent) {
        switch (keyEvent.getCode()) {
            case TAB:
                keyEvent.consume();
                return;
            case UP:
            case DOWN: {
                CommandHistory commandHistory = shell.getCommandHistory();
                clearInput();
                print(keyEvent.getCode() == KeyCode.UP ? commandHistory.prev() : commandHistory.next());
                keyEvent.consume();
                return;
            }
            case LEFT:
            case BACK_SPACE:
                if (inputArea.getCaretPosition() <= lastPromptPosition) {
                    keyEvent.consume();
                }
                return;
            case HOME:
                inputArea.positionCaret(lastPromptPosition);
                keyEvent.consume();
                return;
            case ENTER: {
                if (shell.isWaiting()) {
                    break;
                }
                inputArea.positionCaret(inputArea.getLength());
                final String promptString = shell.getPromptString();
                String content = inputArea.getText();
                String inputLine = content.substring(content.lastIndexOf('\n') + 1);
                inputLine = inputLine.substring(inputLine.indexOf(promptString) + promptString.length());
                eis.queue.add(new StringReader(inputLine + "\n"));
            }
        }
        if (inputArea.getCaretPosition() < lastPromptPosition) {
            inputArea.positionCaret(inputArea.getLength());
        }
    }

    void initShell(Kernel kernel) {
        this.kernel = kernel;
        shell = new Shell(kernel, eis, eos);
        shell.setWaitInputHandler(() -> Platform.runLater(() ->
                lastPromptPosition = inputArea.getLength()
        ));
        shell.setWaitProcessHandler(() -> Platform.runLater(() ->
                inputArea.setEditable(false)
        ));
        shell.setWakeProcessHandler(() -> Platform.runLater(() ->
                inputArea.setEditable(true)
        ));
        shell.setClearHandler(() -> Platform.runLater(inputArea::clear));
        shell.setExitHandler(() -> Platform.runLater(this::handleAppClose));
        shell.registerCommandHandler(new Command("edit") {
            @Override
            public void execute(String args) {
                Platform.runLater(() -> {
                    FilePath filePath = shell.getFilePath(args);
                    if (filePath.exists()) {
                        try {
                            FXMLLoader editorLoader = new FXMLLoader(getClass().getResource("Editor.fxml"));
                            Parent root = editorLoader.load();
                            EditorController editorController = editorLoader.getController();
                            editorController.setFilePath(filePath);
                            editorController.setExitHandler(message -> {
                                if (message != null) {
                                    print(message + "\n");
                                }
                                stage.getScene().setRoot(itsRoot);
                                stage.setTitle(WINDOW_TITLE);
                            });
                            stage.getScene().setRoot(root);
                        } catch (IOException e) {
                            print(e.getMessage());
                            e.printStackTrace();
                        }
                    } else {
                        print(filePath.getPath() + ": No such file\n");
                    }
                });
            }
        });

        shell.registerCommandHandler(new Command("monitor") {
            @Override
            public void execute(String args) {
                Platform.runLater(() -> {
                    if (monitorStage == null) {
                        monitorStage = loadMonitorStage();
                    }
                    if (monitorStage != null) {
                        monitorStage.show();
                    }
                });
            }
        });
        shellThread = new Thread(shell::run, "Thread-Shell");
        shellThread.start();
    }

    private Stage loadMonitorStage() {
        Stage stage = new Stage();
        FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("Monitor.fxml"));
        Scene monitorScene;
        try {
            monitorScene = new Scene(fxmlLoader.load(), 800, 600);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        MonitorController controller = fxmlLoader.getController();
        controller.init(kernel);
        stage.setTitle("Monitor");
        stage.setScene(monitorScene);
        stage.getIcons().add(new Image(this.getClass().getResourceAsStream("monitor.png")));
        stage.setOnHidden(event -> controller.handleHidden());
        stage.setOnShown(event -> controller.handleShown());
        return stage;
    }

    private class EmuInputStream extends java.io.InputStream {
        final BlockingQueue<StringReader> queue = new LinkedBlockingDeque<>();
        private StringReader stringReader;

        @Override
        public int read() throws IOException {
            try {
                if (stringReader == null) {
                    stringReader = queue.take();
                }
                int value = stringReader.read();
                if (value == -1) {
                    stringReader = null;
                }
                return value;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    private class EmuOutputStream extends java.io.OutputStream {
        @Override
        public void write(int b) throws IOException {
            Platform.runLater(() -> inputArea.appendText(Character.toString((char) b)));
        }
    }
}