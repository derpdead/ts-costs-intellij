package local.tscosts;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.util.ui.JBUI;

final class CodePreview extends EditorTextField {
    CodePreview(Project project, Disposable parent, String name) {
        super(EditorFactory.getInstance().createDocument(""), project,
            FileTypeManager.getInstance().getFileTypeByExtension("txt"), true, false);
        setDisposedWith(parent);
        setFontInheritedFromLAF(false);
        setBorder(JBUI.Borders.empty(4));
        getAccessibleContext().setAccessibleName(name);
        addSettingsProvider(editor -> {
            var settings = editor.getSettings();
            settings.setLineNumbersShown(true);
            settings.setUseSoftWraps(true);
            settings.setUseCustomSoftWrapIndent(true);
            settings.setCustomSoftWrapIndent(2);
            settings.setAdditionalLinesCount(0);
            settings.setAdditionalColumnsCount(0);
            settings.setRightMarginShown(false);
            settings.setFoldingOutlineShown(false);
            settings.setLineMarkerAreaShown(false);
            settings.setGutterIconsShown(false);
            settings.setCaretRowShown(false);
            settings.setDndEnabled(false);
            editor.setHorizontalScrollbarVisible(false);
            editor.setVerticalScrollbarVisible(true);
        });
    }

    void showCode(String text, String filename) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        setNewDocumentAndFileType(FileTypeManager.getInstance().getFileTypeByFileName(filename),
            EditorFactory.getInstance().createDocument(normalized));
        setCaretPosition(0);
    }
}
