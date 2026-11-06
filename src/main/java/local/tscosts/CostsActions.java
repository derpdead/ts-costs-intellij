package local.tscosts;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.intellij.util.EnvironmentUtil;

public final class CostsActions {
    private CostsActions() {}

    public static final class Reload extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent event) {
            if (event.getProject() != null) CostsService.get(event.getProject()).reload(true);
        }
    }

    public static final class Import extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent event) {
            if (event.getProject() != null) importProfile(event.getProject());
        }
    }

    public static void importProfile(Project project) {
        FileChooser.chooseFile(FileChooserDescriptorFactory.singleFile().withTitle("Select a TS Costs Profile JSON"),
            project, null, file -> CostsService.get(project).importProfile(Path.of(file.getPath())));
    }

    public static final class Settings extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent event) {
            if (event.getProject() != null) new CostsSettingsDialog(event.getProject()).show();
        }
    }

    public static final class Toggle extends ToggleAction {
        @Override public boolean isSelected(AnActionEvent event) {
            return event.getProject() != null && CostsService.get(event.getProject()).enabled();
        }
        @Override public void setSelected(AnActionEvent event, boolean selected) {
            if (event.getProject() != null) CostsService.get(event.getProject()).setEnabled(selected);
        }
    }

    public static final class Record extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent event) {
            if (event.getProject() != null) record(event.getProject());
        }
    }

    public static void record(Project project) {
        CostsService service = CostsService.get(project);
        if (!Files.exists(service.projectRoot().resolve("tsconfig.json"))
            && !Files.exists(service.projectRoot().resolve("nuxt.config.ts"))
            && !Files.exists(service.projectRoot().resolve("nuxt.config.mjs"))) {
            if (!new CostsSettingsDialog(project).showAndGet()) return;
        }
        if (!service.recording.compareAndSet(false, true)) return;
        FileDocumentManager.getInstance().saveAllDocuments();
        var settings = CostsSettings.get(project).getState();
        Path root = service.projectRoot();
        String configuredNode = settings.node;
        String configuredConfig = settings.config;
        service.setStatus("Recording · preparing framework types, full check, CPU trace and source maps…");
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Record TypeScript Costs", true) {
            @Override public void run(ProgressIndicator indicator) {
                try {
                    Path bridge = root.resolve("node_modules/.cache/ts-costs/bridge");
                    Files.createDirectories(bridge);
                    for (String file : List.of("profile.mjs", "project.mjs", "compiler.cjs", "wrapper-hook.cjs",
                        "capture.cjs", "export.mjs", "mapping.mjs", "trace.mjs", "context.mjs")) {
                        try (InputStream input = CostsActions.class.getResourceAsStream("/bridge/" + file)) {
                            if (input == null) throw new IOException("Missing bundled bridge: " + file);
                            Files.write(bridge.resolve(file), input.readAllBytes());
                        }
                    }
                    String node = configuredNode;
                    if (node.isBlank()) {
                        Path executable = findNode();
                        if (executable == null) throw new IOException("Node.js was not found. Set its executable in TS Costs Settings.");
                        node = executable.toAbsolutePath().toString();
                    }
                    indicator.setIndeterminate(true);
                    indicator.setText("Regenerating types and recording compiler work");
                    List<String> args = new ArrayList<>(List.of(node, bridge.resolve("profile.mjs").toString(), "--project", root.toString()));
                    if (!configuredConfig.isBlank()) args.addAll(List.of("--config", configuredConfig));
                    GeneralCommandLine command = new GeneralCommandLine(args)
                        .withWorkDirectory(root.toFile()).withCharset(StandardCharsets.UTF_8);
                    long started = System.currentTimeMillis();
                    CapturingProcessHandler handler = new CapturingProcessHandler(command);
                    handler.setShouldDestroyProcessRecursively(true);
                    ProcessOutput output = handler.runProcessWithProgressIndicator(indicator);
                    Files.writeString(bridge.getParent().resolve("record.log"), output.getStdout() + output.getStderr());
                    if (output.isCancelled()) {
                        service.setStatus("Recording cancelled · previous profile preserved");
                        return;
                    }
                    Path profile = bridge.getParent().resolve("latest-profile.json");
                    if (!Files.exists(profile) || Files.getLastModifiedTime(profile).toMillis() < started) {
                        throw new IOException("No fresh profile. Open node_modules/.cache/ts-costs/record.log for the compiler error.");
                    }
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) return;
                        service.importProfile(profile);
                        if (output.getExitCode() != 0) service.notifyUser(
                            "Profile saved with compiler errors. Details are in record.log.", NotificationType.WARNING);
                    });
                } catch (Exception exception) {
                    if (!indicator.isCanceled()) {
                        service.setStatus("Recording failed · " + exception.getMessage());
                        service.notifyUser(exception.getMessage(), NotificationType.ERROR);
                    }
                } finally {
                    service.recording.set(false);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!project.isDisposed()) service.refreshView();
                    });
                }
            }
        });
    }

    private static Path findNode() {
        String value = EnvironmentUtil.getValue("PATH");
        if (value == null) return null;
        String filename = System.getProperty("os.name").startsWith("Windows") ? "node.exe" : "node";
        for (String directory : value.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            try {
                Path executable = Path.of(directory).resolve(filename);
                if (Files.isRegularFile(executable) && Files.isExecutable(executable)) return executable;
            } catch (java.nio.file.InvalidPathException ignored) {}
        }
        return null;
    }
}
