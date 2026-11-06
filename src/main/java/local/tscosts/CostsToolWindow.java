package local.tscosts;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

public final class CostsToolWindow implements ToolWindowFactory {
    private record Row(ProfileData.FileCost file, ProfileData.Hint hint) {
        double milliseconds() { return hint == null ? file.fileMs : hint.ms; }
    }

    @Override public void createToolWindowContent(Project project, ToolWindow window) {
        View view = new View(project);
        var content = ContentFactory.getInstance().createContent(view.panel, "", false);
        content.setDisposer(view);
        window.getContentManager().addContent(content);
    }

    private static final class View implements Disposable {
        private final Project project;
        private final CostsService service;
        private final JPanel panel = new JPanel(new BorderLayout());
        private final JTabbedPane tabs = new JTabbedPane();
        private final JPanel overview = new OverviewPanel();
        private final JBScrollPane overviewScroll = new JBScrollPane(overview);
        private final JLabel status = new JLabel();
        private final JButton record = new JButton("Record", AllIcons.Actions.StartDebugger);
        private final JBTextField search = new JBTextField();
        private final JCheckBox currentFile = new JCheckBox("Current file");
        private final JCheckBox inlays;
        private final Browser hotspots = new Browser(false);
        private final Browser files = new Browser(true);
        private final JPanel explanationPanel = new OverviewPanel();
        private final JBScrollPane explanationScroll = new JBScrollPane(explanationPanel);
        private final JEditorPane explanation = html("");
        private final JEditorPane evidence = html("");
        private final JPanel sourcePanel = new JPanel(new BorderLayout(0, 6));
        private final CodePreviewPanel source;
        private final CodePreviewPanel generated;
        private boolean disposed;
        private ProfileData displayed;
        private ProfileData.Hint displayedHint;
        private ProfileData.FileCost displayedFile;

        View(Project project) {
            this.project = project;
            service = CostsService.get(project);
            source = new CodePreviewPanel(project, this, "Recorded source");
            generated = new CodePreviewPanel(project, this, "Generated TypeScript");
            evidence.addHyperlinkListener(event -> {
                if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null
                    && event.getURL().toExternalForm().startsWith("https://github.com/microsoft/TypeScript/wiki/Performance#")) {
                    com.intellij.ide.BrowserUtil.browse(event.getURL());
                }
            });
            inlays = new JCheckBox("Inlays", service.enabled());
            panel.setBorder(JBUI.Borders.empty(8));
            JPanel top = new JPanel(new BorderLayout(0, 8));
            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            toolbar.setBorder(JBUI.Borders.emptyBottom(8));
            record.addActionListener(event -> CostsActions.record(project));
            JButton reload = new JButton(AllIcons.Actions.Refresh);
            reload.setToolTipText("Reload local profile");
            reload.getAccessibleContext().setAccessibleName("Reload local profile");
            reload.addActionListener(event -> service.reload(true));
            JButton settings = new JButton("Settings…");
            settings.addActionListener(event -> new CostsSettingsDialog(project).show());
            JButton importProfile = new JButton("Import…");
            importProfile.addActionListener(event -> CostsActions.importProfile(project));
            inlays.addActionListener(event -> service.setEnabled(inlays.isSelected()));
            toolbar.add(record);
            toolbar.add(reload);
            toolbar.add(importProfile);
            toolbar.add(settings);
            toolbar.add(inlays);
            top.add(toolbar, BorderLayout.NORTH);
            search.getEmptyText().setText("Search files, checks or type names");
            search.getAccessibleContext().setAccessibleName("Search TS costs");
            search.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent event) { filter(); }
                public void removeUpdate(DocumentEvent event) { filter(); }
                public void changedUpdate(DocumentEvent event) { filter(); }
            });
            currentFile.addActionListener(event -> filter());
            JPanel filters = new JPanel(new BorderLayout(8, 0));
            filters.add(search);
            filters.add(currentFile, BorderLayout.EAST);
            top.add(filters, BorderLayout.SOUTH);
            panel.add(top, BorderLayout.NORTH);
            overview.setBorder(JBUI.Borders.empty(16, 4));
            overviewScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            tabs.addTab("Overview", overviewScroll);
            sourcePanel.setBorder(JBUI.Borders.empty(0, 8));
            sourcePanel.add(source);
            JPanel details = new JPanel(new BorderLayout(0, 12));
            details.add(sourcePanel, BorderLayout.NORTH);
            details.add(evidence);
            explanationPanel.add(explanation, BorderLayout.NORTH);
            explanationPanel.add(details);
            explanationScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            JTabbedPane detailTabs = new JTabbedPane();
            detailTabs.addTab("Why this cost", explanationScroll);
            detailTabs.addTab("Generated code", generated);
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, hotspots.component, detailTabs);
            split.setResizeWeight(0.55);
            split.setBorder(JBUI.Borders.empty());
            split.setDividerLocation(JBUI.scale(260));
            tabs.addTab("Hotspots", split);
            tabs.addTab("Files", files.component);
            panel.add(tabs);
            status.setBorder(JBUI.Borders.emptyTop(8));
            status.setToolTipText("Historical compiler measurements. Edit a source file to hide its stale inlays.");
            panel.add(status, BorderLayout.SOUTH);
            service.onViewChanged(this::refresh);
            refresh();
        }

        private void refresh() {
            status.setText(service.status());
            record.setEnabled(!service.recording.get());
            record.setText(service.recording.get() ? "Recording…" : "Record");
            inlays.setSelected(service.enabled());
            ProfileData profile = service.profile();
            if (displayed != profile) {
                displayed = profile;
                displayedHint = null;
                displayedFile = null;
                overview.removeAll();
                renderOverview();
                filter();
                showEvidence(null);
                overview.revalidate();
                overview.repaint();
                SwingUtilities.invokeLater(() -> overviewScroll.getVerticalScrollBar().setValue(0));
            }
            if (currentFile.isSelected()) filter();
            if (service.selectedFile != null && (service.selectedHint != displayedHint || service.selectedFile != displayedFile)) {
                displayedHint = service.selectedHint;
                displayedFile = service.selectedFile;
                if (displayedHint != null) {
                    tabs.setSelectedIndex(1);
                    showEvidence(new Row(displayedFile, displayedHint));
                } else {
                    search.setText(displayedFile.path);
                    tabs.setSelectedIndex(2);
                }
            }
        }

        private void renderOverview() {
            if (displayed == null) {
                overview.add(html("<h2>Understand your TypeScript costs</h2><p>Choose the package directory in Settings, then Record.</p>"
                    + "<p>Record prepares Nuxt types and captures a fresh compiler trace, CPU profile and Vue source maps.</p>"
                    + "<p>Click a cost badge in the editor to inspect its measured scope and generated code.</p>"
                    + "<p>All measurements stay on your machine.</p>"), BorderLayout.NORTH);
                return;
            }
            ProfileData.Summary s = displayed.summary;
            JPanel cards = new JPanel(new GridLayout(0, 2, 10, 10));
            card(cards, "RECORD WALL TIME", s == null ? null : s.wallMs, false, "Preparation + compiler + profile export");
            card(cards, "COMPILER CPU", s == null ? null : s.cpuMs, false, "User + system CPU · all compiler work");
            card(cards, "PEAK PROCESS RSS", s == null ? null : s.peakRssBytes, true, "Whole compiler process · high-water mark");
            card(cards, "TYPESCRIPT MEMORY", s == null ? null : s.tsMemoryBytes, true, "Compiler-reported memory used");
            card(cards, "TYPE CHECKING", s == null ? null : s.checkMs, false, "Extended diagnostics · check phase");
            card(cards, "PREPARE / WRAPPER", s == null ? null : s.prepareMs, false, "Framework generation and wrapper overhead");
            overview.add(cards, BorderLayout.NORTH);
            String phases = s == null ? "Record again to collect CPU and memory metrics."
                : "<h3>Compiler phases</h3><p>Parse " + time(s.parseMs) + " &nbsp; · &nbsp; Bind " + time(s.bindMs)
                    + "<br>Program " + time(s.programMs) + " &nbsp; · &nbsp; Type dump " + time(s.dumpMs) + "</p>"
                    + "<p>" + s.uniqueEvents + " unique mapped events · " + displayed.files.size() + " source files · "
                    + s.projects + " compiler project(s)<br>Mapped event coverage: " + time(s.mappedMs)
                    + " (overlapping spans counted once per compiler thread).</p>"
                    + "<p>Compiler exit: " + s.exitCode + (s.exitCode == 0 ? " · completed" : " · type errors were reported") + "</p>";
            overview.add(html("<h3>" + escape(s == null ? "Legacy profile" : s.framework) + " · measured " + escape(displayed.measuredAt) + "</h3>"
                + phases + "<h3>Reading these numbers</h3>"
                + "<p>Source badges show inclusive elapsed compiler time. Nested checks and both endpoints of a type relation overlap; their labels must not be added.</p>"
                + "<p>CPU and memory belong to the compiler process, including profiling overhead. They cannot be assigned reliably to individual types or lines.</p>"
                + "<p>Across sequential projects, CPU is summed and memory is the maximum. Program time includes parsing; phase values are not additive.</p>"
                + "<p>Package: " + escape(service.projectRoot().toString()) + "</p>"), BorderLayout.CENTER);
        }

        private static void card(JPanel parent, String name, Double value, boolean memory, String note) {
            JPanel card = new JPanel(new BorderLayout(0, 5));
            card.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(12)));
            JLabel title = new JLabel(name);
            title.setForeground(JBColor.GRAY);
            title.setFont(title.getFont().deriveFont(10f));
            JLabel number = new JLabel(value == null ? "Not measured" : memory ? bytes(value) : time(value));
            number.setFont(number.getFont().deriveFont(Font.BOLD, 22f));
            JLabel caption = new JLabel(note);
            caption.setFont(caption.getFont().deriveFont(10f));
            caption.setToolTipText(note);
            card.add(title, BorderLayout.NORTH);
            card.add(number);
            card.add(caption, BorderLayout.SOUTH);
            parent.add(card);
        }

        private void filter() {
            if (hotspots == null || files == null || service == null) return;
            String query = search.getText().toLowerCase(Locale.ROOT).trim();
            VirtualFile active = FileEditorManager.getInstance(project).getSelectedFiles().length == 0 ? null
                : FileEditorManager.getInstance(project).getSelectedFiles()[0];
            List<Row> hintRows = new ArrayList<>(), fileRows = new ArrayList<>();
            if (displayed != null) for (ProfileData.FileCost file : displayed.files) {
                if (currentFile.isSelected() && (active == null || !service.projectRoot().resolve(file.path).toString().equals(active.getPath()))) continue;
                boolean fileMatch = file.path.toLowerCase(Locale.ROOT).contains(query);
                if (fileMatch) fileRows.add(new Row(file, null));
                for (ProfileData.Hint hint : file.hints) {
                    if (fileMatch || (hint.label() + " " + hint.subject + " " + hint.snippet).toLowerCase(Locale.ROOT).contains(query)) {
                        hintRows.add(new Row(file, hint));
                    }
                }
            }
            hintRows.sort(Comparator.comparingDouble(Row::milliseconds).reversed());
            fileRows.sort(Comparator.comparingDouble(Row::milliseconds).reversed());
            hotspots.setRows(hintRows);
            files.setRows(fileRows);
        }

        private void showEvidence(Row row) {
            ProfileData profile = displayed;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed && !project.isDisposed() && displayed == profile) showEvidenceInContext(row);
            }, ModalityState.nonModal());
        }

        private void showEvidenceInContext(Row row) {
            if (row == null || row.hint == null) {
                explanation.setText(document("<h3>Select a hotspot</h3><p>Inspect measured scope, source mapping and Vue-generated code.</p>"));
                evidence.setText(document(""));
                sourcePanel.setVisible(false);
                generated.showMessage("Generated code is available for Vue checks recorded with TS Costs 0.2 or later.");
                resetExplanationScroll();
                return;
            }
            var h = row.hint;
            String filename = row.file.path.substring(row.file.path.lastIndexOf('/') + 1);
            String subject = h.subject != null && h.subject.contains(" · ") ? "<p>" + escape(h.subject) + "</p>" : "";
            explanation.setText(document("<h2>" + escape(h.label()) + " · " + time(h.ms) + "</h2>"
                + "<p><b>" + escape(filename) + ":" + h.line + "</b> · recorded snapshot</p>" + subject
                + checkShare(h.ms)
                + "<h3>What TypeScript checked</h3><p>" + escape(h.explanation == null ? h.detail : h.explanation) + "</p>"
                + CostExplanation.finding(h)));
            explanation.setToolTipText(row.file.path + ":" + h.line);
            String snippet = h.sourceSnippet == null ? h.snippet : h.sourceSnippet;
            source.showCode(snippet == null ? "" : snippet, row.file.path, h.sourceTruncated);
            int lines = snippet == null ? 1 : (int) snippet.lines().count();
            source.setPreferredSize(new Dimension(0, JBUI.scale(34 + Math.min(240, Math.max(64, lines * 20 + 16)))));
            sourcePanel.setVisible(true);
            evidence.setText(document(CostExplanation.details(h, displayed.interpretation)));
            explanation.setCaretPosition(0);
            evidence.setCaretPosition(0);
            boolean hasGenerated = h.generatedSnippet != null && !h.generatedSnippet.isBlank();
            if (hasGenerated) generated.showCode(h.generatedSnippet, "generated.ts", h.generatedTruncated);
            else generated.showMessage("No generated source was captured for this event. Type relations and ordinary TypeScript code have no Vue declaration snippet.");
            explanationPanel.revalidate();
            resetExplanationScroll();
        }

        private String checkShare(double milliseconds) {
            Double total = displayed.summary == null ? null : displayed.summary.checkMs;
            if (total == null || total <= 0 || milliseconds > total) return "<p>Inclusive elapsed compiler time.</p>";
            double percent = milliseconds / total * 100;
            String share = percent > 0 && percent < 0.01 ? "&lt;0.01" : String.format(Locale.ROOT, "%.2f", percent);
            return "<p>" + share + "% of the " + time(total) + " type-checking phase. Inclusive time; events overlap.</p>";
        }

        private void resetExplanationScroll() {
            SwingUtilities.invokeLater(() -> {
                if (!disposed) explanationScroll.getVerticalScrollBar().setValue(0);
            });
        }

        private void navigate(Row row) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) navigateInContext(row);
            }, ModalityState.nonModal());
        }

        private void navigateInContext(Row row) {
            try {
                var source = service.projectRoot().resolve(row.file.path).normalize();
                if (!source.toRealPath().startsWith(service.projectRoot().toRealPath())) return;
                VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
                if (file != null) new OpenFileDescriptor(project, file, row.hint == null ? 0 : row.hint.start).navigate(true);
            } catch (Exception exception) { service.setStatus("Cannot open measured source: " + exception.getMessage()); }
        }

        private final class Browser {
            private final JPanel component = new JPanel(new BorderLayout(0, 6));
            private final DefaultTableModel model;
            private final JBTable table;
            private final JLabel count = new JLabel();
            private final JButton previous = new JButton("Previous");
            private final JButton next = new JButton("Next");
            private final boolean fileMode;
            private List<Row> rows = List.of();
            private int page;
            private static final int PAGE_SIZE = 40;

            Browser(boolean fileMode) {
                this.fileMode = fileMode;
                component.setBorder(JBUI.Borders.emptyTop(8));
                model = new DefaultTableModel(fileMode ? new Object[] {"File", "Check time", "Mapped regions"}
                    : new Object[] {"File", "Line", "Elapsed", "Check"}, 0) {
                    @Override public boolean isCellEditable(int row, int column) { return false; }
                    @Override public Class<?> getColumnClass(int column) {
                        return fileMode ? column == 1 ? Double.class : column == 2 ? Integer.class : String.class
                            : column == 1 ? Integer.class : column == 2 ? Double.class : String.class;
                    }
                };
                table = new JBTable(model);
                table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                table.setFillsViewportHeight(true);
                table.setRowHeight(JBUI.scale(27));
                table.getColumnModel().getColumn(0).setPreferredWidth(320);
                table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
                    @Override public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                            boolean selected, boolean focused, int row, int column) {
                        super.getTableCellRendererComponent(table, value, selected, focused, row, column);
                        String path = String.valueOf(value);
                        int separator = path.lastIndexOf('/');
                        setText(separator < 0 ? path : path.substring(separator + 1) + " — " + path.substring(0, separator));
                        setToolTipText(path);
                        return this;
                    }
                });
                table.getSelectionModel().addListSelectionListener(event -> {
                    if (!event.getValueIsAdjusting() && table.getSelectedRow() >= 0 && !fileMode) showEvidence(selected());
                });
                table.addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent event) {
                        if (event.getClickCount() != 2 || table.getSelectedRow() < 0) return;
                        Row row = selected();
                        if (fileMode) {
                            search.setText(row.file.path);
                            tabs.setSelectedIndex(1);
                        }
                        navigate(row);
                    }
                });
                DefaultTableCellRenderer numeric = new DefaultTableCellRenderer() {
                    @Override protected void setValue(Object value) {
                        setText(value instanceof Double milliseconds ? time(milliseconds) : "");
                    }
                };
                numeric.setHorizontalAlignment(SwingConstants.RIGHT);
                numeric.setToolTipText(fileMode ? "Recorded file-check time"
                    : "Inclusive elapsed compiler time. Events can overlap.");
                table.setDefaultRenderer(Double.class, numeric);
                component.add(new JBScrollPane(table));
                JPanel footer = new JPanel(new BorderLayout());
                JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
                previous.addActionListener(event -> { page--; displayPage(); });
                next.addActionListener(event -> { page++; displayPage(); });
                JButton open = new JButton("Open source");
                open.addActionListener(event -> { if (table.getSelectedRow() >= 0) navigate(selected()); });
                buttons.add(open); buttons.add(previous); buttons.add(next);
                footer.add(count, BorderLayout.WEST);
                footer.add(buttons, BorderLayout.EAST);
                component.add(footer, BorderLayout.SOUTH);
            }
            private Row selected() { return rows.get(page * PAGE_SIZE + table.getSelectedRow()); }
            void setRows(List<Row> value) { rows = value; page = 0; displayPage(); }
            void displayPage() {
                table.clearSelection();
                model.setRowCount(0);
                int start = page * PAGE_SIZE, end = Math.min(rows.size(), start + PAGE_SIZE);
                for (Row row : rows.subList(start, end)) {
                    double ms = row.milliseconds();
                    model.addRow(fileMode ? new Object[] { row.file.path, ms, row.file.hints.size() }
                        : new Object[] { row.file.path, row.hint.line, ms, row.hint.label() });
                }
                previous.setEnabled(page > 0);
                next.setEnabled(end < rows.size());
                count.setText(rows.isEmpty() ? "No matching measurements" : (start + 1) + "–" + end + " of " + rows.size());
            }
        }

        @Override public void dispose() { disposed = true; service.onViewChanged(() -> {}); }
    }

    private static final class OverviewPanel extends JPanel implements Scrollable {
        OverviewPanel() { super(new BorderLayout(0, 16)); }
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(480, 600); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) { return JBUI.scale(20); }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) { return r.height / 2; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static String time(Double value) {
        if (value == null) return "Not measured";
        if (value > 0 && value < 0.01) return String.format(Locale.ROOT, "%.1f µs", value * 1000);
        if (value > 0 && value < 1) return String.format(Locale.ROOT, "%.2f ms", value);
        return value >= 1000 ? String.format(Locale.ROOT, "%.2f s", value / 1000) : String.format(Locale.ROOT, "%.1f ms", value);
    }
    private static String bytes(double value) { return String.format(Locale.ROOT, "%.1f MiB", value / 1024 / 1024); }
    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
    private static String document(String body) {
        return "<html><body style='font-family: sans-serif; padding: 8px; font-size: 11px;'>" + body + "</body></html>";
    }
    private static JEditorPane html(String body) {
        JEditorPane pane = new JEditorPane("text/html", document(body));
        pane.setEditable(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        pane.setOpaque(false);
        pane.setCaretPosition(0);
        return pane;
    }
}
