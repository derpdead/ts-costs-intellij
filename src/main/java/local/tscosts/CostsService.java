package local.tscosts;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service(Service.Level.PROJECT)
public final class CostsService implements Disposable {
    private final Project project;
    private final Map<Editor, List<Inlay<?>>> inlays = new IdentityHashMap<>();
    private final Map<Editor, Long> revisions = new IdentityHashMap<>();
    private final Map<Editor, EditorMouseMotionListener> mouseListeners = new IdentityHashMap<>();
    private final Map<Editor, EditorMouseListener> clickListeners = new IdentityHashMap<>();
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile ProfileData profile;
    private volatile Map<String, ProfileData.FileCost> costs = Map.of();
    private Path imported;
    private String status = "Choose the package directory in Settings, then Record a full profile.";
    private Runnable viewChanged = () -> {};
    public ProfileData.FileCost selectedFile;
    public ProfileData.Hint selectedHint;
    public final AtomicBoolean recording = new AtomicBoolean();

    public static CostsService get(Project project) { return project.getService(CostsService.class); }

    public CostsService(Project project) {
        this.project = project;
        EditorFactory factory = EditorFactory.getInstance();
        factory.addEditorFactoryListener(new EditorFactoryListener() {
            @Override public void editorCreated(EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                if (editor.getProject() == project) ApplicationManager.getApplication().invokeLater(() -> attach(editor));
            }
            @Override public void editorReleased(EditorFactoryEvent event) {
                clear(event.getEditor());
                inlays.remove(event.getEditor());
                revisions.remove(event.getEditor());
                EditorMouseMotionListener listener = mouseListeners.remove(event.getEditor());
                if (listener != null) event.getEditor().removeEditorMouseMotionListener(listener);
                EditorMouseListener click = clickListeners.remove(event.getEditor());
                if (click != null) event.getEditor().removeEditorMouseListener(click);
            }
        }, this);
        factory.getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(DocumentEvent event) {
                for (Editor editor : factory.getEditors(event.getDocument(), project)) {
                    clear(editor);
                    revisions.remove(editor);
                }
                if (fileCost(event.getDocument()) != null) {
                    status = "File changed: its measured hints are hidden until a matching profile is loaded";
                    viewChanged.run();
                }
            }
        }, this);
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            new FileEditorManagerListener() {
                @Override public void fileOpened(FileEditorManager manager, VirtualFile file) { refreshEditors(); }
                @Override public void selectionChanged(com.intellij.openapi.fileEditor.FileEditorManagerEvent event) { refreshView(); }
            });
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) refreshEditors();
        });
    }

    public Path projectRoot() {
        String configured = CostsSettings.get(project).getState().projectRoot;
        return Path.of(configured.isBlank() ? project.getBasePath() : configured).toAbsolutePath().normalize();
    }

    public Path profilePath() {
        return imported != null ? imported : projectRoot().resolve("node_modules/.cache/ts-costs/latest-profile.json");
    }

    public void importProfile(Path path) { imported = path; reload(true); }
    public ProfileData profile() { return profile; }
    public String status() { return status; }
    public boolean enabled() { return CostsSettings.get(project).getState().inlays; }
    public void onViewChanged(Runnable listener) { viewChanged = listener; }
    public void refreshView() { viewChanged.run(); }
    public void setEnabled(boolean value) {
        CostsSettings.get(project).getState().inlays = value;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) { revisions.clear(); refreshEditors(); }
        }, ModalityState.nonModal());
    }
    public void settingsChanged() { imported = null; reload(false); }
    public void setStatus(String value) {
        ApplicationManager.getApplication().invokeLater(() -> { if (!project.isDisposed()) { status = value; viewChanged.run(); } });
    }
    public void showDetails(ProfileData.FileCost file, ProfileData.Hint hint) {
        selectedFile = file;
        selectedHint = hint;
        var window = ToolWindowManager.getInstance(project).getToolWindow("TS Costs");
        if (window != null) window.activate(() -> viewChanged.run());
    }

    public void reload(boolean notify) {
        if (!loading.compareAndSet(false, true)) return;
        Path path = profilePath();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Load TypeScript Costs", false) {
            @Override public void run(ProgressIndicator indicator) {
                try {
                    ProfileData data = ProfileData.read(path);
                    if (!Path.of(data.projectRoot).toRealPath().equals(projectRoot().toRealPath())) {
                        throw new IllegalArgumentException("Profile belongs to another project directory");
                    }
                    Map<String, ProfileData.FileCost> index = new HashMap<>();
                    for (ProfileData.FileCost file : data.files) {
                        index.put(projectRoot().resolve(file.path).normalize().toString(), file);
                    }
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) return;
                        profile = data;
                        selectedFile = null;
                        selectedHint = null;
                        costs = Map.copyOf(index);
                        status = "Measured " + data.measuredAt + " · " + data.files.size() + " files · " + data.unmappedEvents + " unmapped regions omitted";
                        revisions.clear();
                        refreshEditors();
                        viewChanged.run();
                        if (notify) notifyUser("Loaded costs for " + data.files.size() + " files", NotificationType.INFORMATION);
                    });
                } catch (Exception exception) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) return;
                        profile = null;
                        costs = Map.of();
                        status = "No matching TS Costs profile: " + exception.getMessage();
                        revisions.clear();
                        refreshEditors();
                        viewChanged.run();
                        if (notify) notifyUser(status, NotificationType.ERROR);
                    });
                } finally { loading.set(false); }
            }
        });
    }

    private void attach(Editor editor) {
        if (editor.isDisposed() || inlays.containsKey(editor)) return;
        inlays.put(editor, new ArrayList<>());
        EditorMouseMotionListener listener = new EditorMouseMotionListener() {
            @Override public void mouseMoved(EditorMouseEvent event) {
                Inlay<?> inlay = ReadAction.computeBlocking(() -> event.getInlay() != null ? event.getInlay()
                    : editor.getInlayModel().getElementAt(event.getMouseEvent().getPoint()));
                editor.getContentComponent().setToolTipText(inlay != null && inlay.getRenderer() instanceof CostRenderer renderer
                    ? renderer.tooltip() : null);
            }
        };
        mouseListeners.put(editor, listener);
        editor.addEditorMouseMotionListener(listener);
        EditorMouseListener click = new EditorMouseListener() {
            @Override public void mouseClicked(EditorMouseEvent event) {
                Inlay<?> inlay = ReadAction.computeBlocking(() -> editor.getInlayModel().getElementAt(event.getMouseEvent().getPoint()));
                if (inlay != null && inlay.getRenderer() instanceof CostRenderer renderer) {
                    renderer.activate();
                    event.consume();
                }
            }
        };
        clickListeners.put(editor, click);
        editor.addEditorMouseListener(click);
        render(editor);
    }

    public void refreshEditors() {
        if (project.isDisposed()) return;
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.getProject() == project && !editor.isDisposed()) {
                if (!inlays.containsKey(editor)) attach(editor); else render(editor);
            }
        }
    }

    private ProfileData.FileCost fileCost(Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        return file == null ? null : costs.get(file.getPath());
    }

    private void render(Editor editor) {
        Document document = editor.getDocument();
        long stamp = document.getModificationStamp();
        if (enabled() && revisions.getOrDefault(editor, -1L) == stamp) return;
        clear(editor);
        if (!enabled()) return;
        ProfileData.FileCost file = fileCost(document);
        if (file == null) return;
        String text = document.getText();
        if (!ProfileData.fingerprint(text).equals(file.sha256)) {
            status = "File differs from the measured snapshot: hints hidden; record a new profile";
            return;
        }
        revisions.put(editor, stamp);
        List<Inlay<?>> owned = inlays.computeIfAbsent(editor, ignored -> new ArrayList<>());
        String fileLabel = String.format(Locale.ROOT, "TS file %.1f ms", file.fileMs);
        add(owned, editor.getInlayModel().addAfterLineEndElement(document.getLineEndOffset(0), false,
            new CostRenderer(fileLabel, "Whole-file checking cost. Click for details. " + profile.interpretation, file.fileMs,
                () -> showDetails(file, null))));
        Map<String, ProfileData.Hint> chosen = new HashMap<>();
        for (ProfileData.Hint hint : file.hints) {
            if (hint.end > document.getTextLength() || hint.ms < 1) continue;
            String key = document.getLineNumber(hint.end) + ":" + hint.kind;
            ProfileData.Hint previous = chosen.get(key);
            if (previous == null || hint.ms > previous.ms) chosen.put(key, hint);
        }
        chosen.values().stream().sorted(Comparator.comparingDouble((ProfileData.Hint hint) -> hint.ms).reversed())
            .limit(80).forEach(hint -> {
                String label = String.format(Locale.ROOT, "TS %.1f ms · %s", hint.ms, hint.label());
                String tooltip = "<html>" + escape(hint.event) + " · " + escape(hint.mapping)
                    + "<br>" + escape(hint.explanation == null ? hint.detail : hint.explanation) + "<br>" + escape(hint.snippet)
                    + "<br>Click for evidence and generated code. Inclusive wall time; overlapping labels are not additive.</html>";
                add(owned, editor.getInlayModel().addInlineElement(hint.end, true,
                    new CostRenderer(label, tooltip, hint.ms, () -> showDetails(file, hint))));
            });
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void add(List<Inlay<?>> values, Inlay<?> value) { if (value != null) values.add(value); }

    private void clear(Editor editor) {
        List<Inlay<?>> values = inlays.get(editor);
        if (values != null) {
            for (Inlay<?> inlay : values) if (inlay.isValid()) Disposer.dispose(inlay);
            values.clear();
        }
    }

    public void notifyUser(String text, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("TS Costs").createNotification(text, type).notify(project);
    }

    @Override public void dispose() {
        for (Editor editor : new ArrayList<>(inlays.keySet())) clear(editor);
        inlays.clear();
        revisions.clear();
        mouseListeners.forEach((editor, listener) -> editor.removeEditorMouseMotionListener(listener));
        mouseListeners.clear();
        clickListeners.forEach((editor, listener) -> editor.removeEditorMouseListener(listener));
        clickListeners.clear();
        viewChanged = () -> {};
    }
}
