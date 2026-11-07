package local.tscosts;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.util.Locale;
import javax.swing.*;

final class CodePreviewPanel extends JPanel implements Disposable {
    private final CodePreview code;
    private final JLabel caption;
    private final JCheckBox wrap = new JCheckBox("Wrap lines", true);
    private final JButton copy = new JButton("Copy", AllIcons.Actions.Copy);
    private final JButton expand = new JButton("Expand…");
    private final String name;
    private String text;
    private String filename;
    private boolean disposed;

    CodePreviewPanel(Project project, Disposable parent, String name) {
        this(project, parent, name, true, true);
    }

    private CodePreviewPanel(Project project, Disposable parent, String name, boolean expandable, boolean wrapped) {
        super(new BorderLayout(0, 6));
        this.name = name;
        Disposer.register(parent, this);
        code = new CodePreview(project, this, name);
        code.setWrapLines(wrapped);
        caption = new JLabel(name);
        caption.setFont(caption.getFont().deriveFont(Font.BOLD));
        wrap.setSelected(wrapped);
        wrap.setToolTipText("Wrap long lines in this preview without changing the recorded code");
        copy.setToolTipText("Copy the recorded snippet");
        copy.getAccessibleContext().setAccessibleName("Copy " + name.toLowerCase(Locale.ROOT));
        expand.setToolTipText("Open the recorded snippet in a larger window");
        expand.getAccessibleContext().setAccessibleName("Expand " + name.toLowerCase(Locale.ROOT));
        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(wrap);
        actions.add(copy);
        if (expandable) actions.add(expand);
        toolbar.add(caption);
        toolbar.add(actions, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);
        add(code);
        copy.addActionListener(event -> {
            if (text == null || disposed) return;
            CopyPasteManager.getInstance().setContents(new StringSelection(text));
            copy.setText("Copied");
        });
        wrap.addActionListener(event -> {
            boolean value = wrap.isSelected();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed && !project.isDisposed()) code.setWrapLines(value);
            }, ModalityState.stateForComponent(this));
        });
        expand.addActionListener(event -> {
            String snapshot = text, path = filename;
            boolean value = wrap.isSelected();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed && !project.isDisposed() && snapshot != null) {
                    new PreviewDialog(project, name, snapshot, path, value).show();
                }
            }, ModalityState.stateForComponent(this));
        });
        setActionsEnabled(false);
    }

    void showCode(String value, String path) {
        text = value;
        filename = path;
        code.showCode(value, path);
        int lines = code.getDocument().getLineCount();
        caption.setText(name + " · " + lines + (lines == 1 ? " line" : " lines"));
        caption.setToolTipText(path);
        copy.setToolTipText("Copy the recorded snippet");
        expand.setToolTipText("Open the recorded snippet in a larger window");
        copy.setText("Copy");
        setActionsEnabled(!value.isBlank());
    }

    void showCode(String value, String path, boolean excerpt) {
        showCode(value, path);
        if (excerpt) {
            caption.setText(name + " · excerpt");
            caption.setToolTipText(path + " · captured excerpt was truncated");
            copy.setToolTipText("Copy this truncated recorded excerpt");
            expand.setToolTipText("Expand this truncated recorded excerpt");
        }
    }

    void showMessage(String message) {
        code.showCode(message, "message.txt");
        text = null;
        filename = null;
        caption.setText(name);
        caption.setToolTipText(null);
        copy.setText("Copy");
        setActionsEnabled(false);
    }

    private void setActionsEnabled(boolean enabled) {
        copy.setEnabled(enabled);
        expand.setEnabled(enabled);
        wrap.setEnabled(enabled);
    }

    @Override public void dispose() { disposed = true; }

    private static final class PreviewDialog extends DialogWrapper {
        private final CodePreviewPanel preview;

        PreviewDialog(Project project, String name, String text, String filename, boolean wrapped) {
            super(project, false);
            setTitle(name + " — " + filename.substring(filename.lastIndexOf('/') + 1));
            setResizable(true);
            setCancelButtonText("Close");
            preview = new CodePreviewPanel(project, getDisposable(), name, false, wrapped);
            preview.showCode(text, filename);
            preview.setPreferredSize(new Dimension(JBUI.scale(900), JBUI.scale(560)));
            init();
        }

        @Override protected JComponent createCenterPanel() { return preview; }
        @Override protected Action[] createActions() { return new Action[] {getCancelAction()}; }
        @Override protected String getDimensionServiceKey() { return "TS.Costs.CodePreview"; }
    }
}
