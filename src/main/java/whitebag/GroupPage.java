package whitebag;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * One tab holding a few related pages, with a switch along the top to pick which one shows. Remembers which page
 * you were on (per group, by its page names) between runs.
 */
final class GroupPage extends JPanel {
    /** Where the remembered choices are kept (the app's settings), and how to save them. */
    static volatile java.util.Properties prefs;
    static volatile Runnable save = () -> { };

    private final CardLayout cards = new CardLayout();
    private final JComponent[] pages;
    /** As tall as the page on show (not the tallest one), so a short page doesn't get a long empty sheet. */
    private final JPanel holder = new JPanel(cards) {
        @Override
        public Dimension getPreferredSize() {
            Dimension d = pages[shown].getPreferredSize();
            Insets in = getInsets();
            return new Dimension(d.width + in.left + in.right, d.height + in.top + in.bottom);
        }
    };
    private final String[] names;
    private final String key;
    private int shown = 0;
    private LogTabs.Segments segments;

    /** Shows page i, with its switch lit up (for jumping here from elsewhere). */
    void jump(int i) {
        if (segments != null) segments.choose(i);
        show(i);
    }

    GroupPage(String[] names, JComponent... pages) {
        super(new BorderLayout());
        this.names = names;
        this.pages = pages;
        this.key = "ui.group." + String.join("/", names).replaceAll("[^A-Za-z/]", "");
        setOpaque(false);
        holder.setOpaque(false);
        for (int i = 0; i < pages.length; i++) holder.add(pages[i], names[i]);
        int start = 0;
        try {
            if (prefs != null) start = Math.max(0, Math.min(pages.length - 1, Integer.parseInt(prefs.getProperty(key, "0"))));
        } catch (NumberFormatException ignored) {
        }
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(0, 0, 10, 0));
        top.add(segments = new LogTabs.Segments(names, start, i -> {
            Squeak.boop();
            show(i);
            if (prefs != null) {
                prefs.setProperty(key, String.valueOf(i));
                save.run();
            }
        }));
        add(top, BorderLayout.NORTH);
        add(holder, BorderLayout.CENTER);
        if (start != 0) show(start);
    }

    void show(int i) {
        shown = i;
        cards.show(holder, names[i]);
        holder.revalidate();
        revalidate();
        repaint();
    }

    int shown() {
        return shown;
    }
}
