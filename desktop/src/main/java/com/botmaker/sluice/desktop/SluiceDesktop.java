package com.botmaker.sluice.desktop;

import com.botmaker.sluice.desktop.model.Io;
import com.botmaker.sluice.desktop.model.Prefs;
import com.botmaker.sluice.desktop.model.Theme;
import com.botmaker.sluice.desktop.model.Umbrella;
import com.botmaker.sluice.desktop.ui.Browse;
import com.botmaker.sluice.desktop.ui.ChangelogTab;
import com.botmaker.sluice.desktop.ui.ConfigTab;
import com.botmaker.sluice.desktop.ui.LazyTab;
import com.botmaker.sluice.desktop.ui.ModulesTab;
import com.botmaker.sluice.desktop.ui.ReleaseTab;
import com.botmaker.sluice.desktop.ui.ReleasesTab;
import com.botmaker.sluice.desktop.ui.StartPane;
import com.botmaker.sluice.desktop.ui.Themed;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The window: a start screen until an umbrella is open, then its Modules, Release, Releases, Changelog and Config
 * tabs.
 * Every answer comes from the library; this decides nothing a terminal's {@code sluice} would not.
 */
public final class SluiceDesktop extends Application {

    private static final String TITLE = "sluice";
    private static final List<Integer> ICON_SIZES = List.of(16, 32, 64, 128, 256);

    private final Prefs prefs = Prefs.user();
    private final BorderPane root = new BorderPane();
    private final Label umbrellaPath = new Label("no umbrella open");
    private final Label message = new Label();
    private final Button openOther = new Button("Open…");
    private Stage stage;
    private StartPane start;
    private ReleaseTab release;
    private LazyTab<ConfigTab> config;
    private boolean closeWhenEnded;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Themed.install(Themed.resolve(prefs.theme()));
        Browse.install(getHostServices());

        root.getStyleClass().add("app-root");
        umbrellaPath.getStyleClass().add("umbrella-path");
        message.getStyleClass().add("status-line");
        openOther.setOnAction(e -> showStart());
        Button theme = new Button();
        theme.getStyleClass().add("theme-toggle");
        theme.setText(themeLabel(Themed.current()));
        theme.setOnAction(e -> {
            Theme next = Themed.current().other();
            Themed.set(next);
            prefs.theme(next);
            theme.setText(themeLabel(next));
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, umbrellaPath, openOther, spacer, message, theme);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.getStyleClass().addAll("top-bar", "umbrella-bar");
        root.setTop(bar);

        start = new StartPane(prefs, this::open);
        showStart();

        Scene scene = new Scene(root, 1280, 820);
        Themed.scene(scene);
        stage.setScene(scene);
        stage.setTitle(TITLE);
        icons(stage);
        stage.setOnCloseRequest(e -> {
            if (release != null && release.running()) {
                e.consume();
                askToClose();
            } else if (!configMayGo()) {
                e.consume();
            }
        });
        stage.show();

        getParameters().getUnnamed().stream().findFirst().map(Path::of).ifPresentOrElse(start::tryOpen,
                () -> prefs.recent().stream().findFirst().filter(Umbrella::looksLikeOne).ifPresent(this::open));
    }

    @Override
    public void stop() {
        Io.shutdown();
    }

    private void showStart() {
        if (release != null && release.running()) {
            message.setText("A release is running: it stays open until it ends.");
            return;
        }
        if (!configMayGo()) {
            return;
        }
        root.setCenter(start);
        openOther.setVisible(false);
    }

    private void open(Path dir) {
        message.setText("Reading " + dir + "…");
        Io.async(() -> Umbrella.open(dir)).whenComplete((umbrella, error) -> Platform.runLater(() -> {
            message.setText("");
            if (error != null) {
                start.problem(dir + ": " + Io.message(error));
                root.setCenter(start);
                return;
            }
            prefs.opened(umbrella.root());
            show(umbrella);
        }));
    }

    private void show(Umbrella umbrella) {
        Consumer<String> browse = url -> Browse.open(url, message::setText);
        ModulesTab modules = new ModulesTab(umbrella, browse);
        release = new ReleaseTab(umbrella, browse);
        LazyTab<ReleasesTab> releases = new LazyTab<>("Releases", () -> new ReleasesTab(umbrella, browse));
        LazyTab<ChangelogTab> changelog = new LazyTab<>("Changelog", () -> new ChangelogTab(umbrella));
        config = new LazyTab<>("Config", () -> new ConfigTab(umbrella));
        release.setOnEnded(() -> {
            releases.ifBuilt(ReleasesTab::reload);
            if (closeWhenEnded) {
                stage.close();
            }
        });

        Tab modulesTab = new Tab("Modules", modules);
        Tab releaseTab = new Tab("Release", release);
        releaseTab.setGraphic(release.badge());
        releaseTab.selectedProperty().addListener((o, was, is) -> release.setShowing(is));
        TabPane tabs = new TabPane(modulesTab, releaseTab, releases.tab(), changelog.tab(), config.tab());
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("tab-bar");
        release.setShowing(false);

        root.setCenter(tabs);
        openOther.setVisible(true);
        umbrellaPath.setText(umbrella.root().toString());
        stage.setTitle(TITLE + " — " + umbrella.name());
    }

    private void askToClose() {
        Alert alert = Themed.dialog(new Alert(Alert.AlertType.CONFIRMATION,
                "A release is running. Stop it after the step it is on, and close once it has stopped? "
                        + "Resume goes on from there.", ButtonType.CANCEL, ButtonType.OK), stage);
        alert.setHeaderText("Stop the release and close");
        // The window closes by itself once the run ends, with no close request: the edit is asked about now.
        if (alert.showAndWait().filter(ButtonType.OK::equals).isPresent() && configMayGo()) {
            closeWhenEnded = true;
            release.stop();
            message.setText("Stopping the release; the window closes when it has.");
        }
    }

    /** Whether the Config tab has no unsaved edit, or the user lets it go. */
    private boolean configMayGo() {
        boolean[] may = {true};
        if (config != null) {
            config.ifBuilt(tab -> may[0] = tab.mayDiscard());
        }
        return may[0];
    }

    private static String themeLabel(Theme theme) {
        return theme == Theme.DARK ? "☀ Light" : "☾ Dark";
    }

    private static void icons(Stage stage) {
        for (int size : ICON_SIZES) {
            try (InputStream in = SluiceDesktop.class.getResourceAsStream("/icons/icon-" + size + ".png")) {
                if (in != null) {
                    stage.getIcons().add(new Image(in));
                }
            } catch (IOException ignored) {
                // An icon that will not load leaves the default one.
            }
        }
    }
}
