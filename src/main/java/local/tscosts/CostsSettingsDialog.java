package local.tscosts;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

public final class CostsSettingsDialog extends DialogWrapper {
    private final Project project;
    private final JBTextField root;
    private final JBTextField node;
    private final JBTextField config;

    public CostsSettingsDialog(Project project) {
        super(project);
        this.project = project;
        var state = CostsSettings.get(project).getState();
        root = new JBTextField(CostsService.get(project).projectRoot().toString(), 38);
        node = new JBTextField(state.node);
        node.getEmptyText().setText("Automatic · Node.js from PATH");
        config = new JBTextField(state.config);
        config.getEmptyText().setText("Automatic · Vue / Nuxt / TypeScript");
        setTitle("TS Costs Settings");
        init();
    }

    @Override protected JComponent createCenterPanel() {
        JPanel directory = new JPanel(new BorderLayout(6, 0));
        directory.add(root);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(event -> FileChooser.chooseFile(
            FileChooserDescriptorFactory.singleDir().withTitle("Select the TypeScript Package Directory"),
            project, null, file -> root.setText(file.getPath())));
        directory.add(browse, BorderLayout.EAST);
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Package directory", directory)
            .addLabeledComponent("Node.js executable", node)
            .addLabeledComponent("tsconfig (optional)", config)
            .addComponent(new JLabel("<html>Record regenerates Nuxt types, performs a fresh check and captures Vue source maps.<br>"
                + "Leave tsconfig empty to respect a supported node-based typecheck wrapper.<br>"
                + "An explicit tsconfig uses the automatic compiler runner.<br>"
                + "Profiles and source snapshots stay in node_modules/.cache/ts-costs.</html>"))
            .getPanel();
    }

    @Override protected void doOKAction() {
        try {
            Path directory = Path.of(root.getText().trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(directory)) { setErrorText("Choose an existing package directory."); return; }
            var state = CostsSettings.get(project).getState();
            state.projectRoot = directory.toString();
            state.node = node.getText().trim();
            state.config = config.getText().trim();
            CostsService.get(project).settingsChanged();
            super.doOKAction();
        } catch (RuntimeException exception) { setErrorText(exception.getMessage()); }
    }
}
