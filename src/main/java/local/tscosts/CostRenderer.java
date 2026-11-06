package local.tscosts;

import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;

public final class CostRenderer implements EditorCustomElementRenderer {
    private final String label;
    private final String tooltip;
    private final double milliseconds;
    private final Runnable action;

    public CostRenderer(String label, String tooltip, double milliseconds, Runnable action) {
        this.label = label;
        this.tooltip = tooltip;
        this.milliseconds = milliseconds;
        this.action = action;
    }

    public String tooltip() { return tooltip; }
    public void activate() { action.run(); }

    private Font font(Inlay<?> inlay) {
        Font font = inlay.getEditor().getColorsScheme().getFont(EditorFontType.PLAIN);
        return font.deriveFont(font.getSize2D() * 0.85f);
    }

    @Override
    public int calcWidthInPixels(Inlay inlay) {
        return inlay.getEditor().getContentComponent().getFontMetrics(font(inlay)).stringWidth(label) + 12;
    }

    @Override
    public void paint(Inlay inlay, Graphics graphics, Rectangle region, TextAttributes attributes) {
        Graphics copy = graphics.create();
        try {
            copy.setFont(font(inlay));
            Color background = milliseconds >= 50
                ? new JBColor(new Color(255, 235, 205), new Color(86, 60, 33))
                : new JBColor(new Color(225, 239, 250), new Color(40, 65, 82));
            copy.setColor(background);
            copy.fillRoundRect(region.x + 2, region.y + 1, region.width - 4, region.height - 2, 8, 8);
            copy.setColor(new JBColor(new Color(50, 74, 96), new Color(180, 216, 240)));
            int baseline = region.y + (region.height - copy.getFontMetrics().getHeight()) / 2
                + copy.getFontMetrics().getAscent();
            copy.drawString(label, region.x + 6, baseline);
        } finally { copy.dispose(); }
    }
}
