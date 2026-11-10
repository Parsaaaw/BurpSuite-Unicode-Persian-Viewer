import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Range;
import burp.api.montoya.core.Registration;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.persistence.Preferences;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.Theme;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.hotkey.HotKeyContext;
import burp.api.montoya.ui.hotkey.HotKeyEvent;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class DecoderExtension implements BurpExtension {

    private static final int MAX_CHARS = 800_000;
    private static final int BASE_LATIN = 13;
    private static final int BASE_PERSIAN = 16;
    private static final int BASE_INPUT = 12;
    private static final int MIN_DELTA = -6;
    private static final int MAX_DELTA = 30;
    private static final String PREF_SEND = "hotkey_send";
    private static final String PREF_PREVIEW = "hotkey_preview";
    private static final String PREF_FONT = "font_delta";
    private static final String PREF_FONT_REQ = "font_delta_req";
    private static final String PREF_FONT_RESP = "font_delta_resp";
    private static final String PREF_LINK = "zoom_link";
    private static final String PREF_S_CASE = "search_case";
    private static final String PREF_S_REGEX = "search_regex";
    private static final String PREF_S_AUTO = "search_auto";
    private static final String DEFAULT_SEND = "Ctrl+Alt+U";
    private static final String DEFAULT_PREVIEW = "Ctrl+Alt+P";
    private static final int COLOR_LIMIT = 150_000;
    private static final int MAX_HITS = 5000;
    private static final Pattern TOKEN = Pattern.compile(
            "\"(?:[^\"\\\\\\n]|\\\\.)*\"(\\s*:)?|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|\\b(?:true|false|null)\\b");

    private MontoyaApi api;
    private JPanel root;
    private JTextArea input;
    private JTextPane output;
    private JTextPane previewPane;
    private JFrame previewFrame;
    private JCheckBox prettyBox;
    private JCheckBox rtlBox;
    private JCheckBox digitsBox;
    private JTextField sendKeyField;
    private JTextField previewKeyField;
    private JLabel statusLabel;
    private Timer timer;
    private Timer themeTimer;
    private java.beans.PropertyChangeListener lafListener;
    private Registration sendKeyReg;
    private Registration previewKeyReg;
    private String lastPlain = "";
    private String previewRaw;
    private String persianFamily;
    private int fontDelta;
    private int reqDelta;
    private int respDelta;
    private boolean linkZoom;
    private boolean searchCase;
    private boolean searchRegex;
    private boolean searchAuto;
    private String latinFamilyName;
    private final SimpleAttributeSet latinAttr = new SimpleAttributeSet();
    private final SimpleAttributeSet persianAttr = new SimpleAttributeSet();

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("Unicode and Persian Viewer");

        Set<String> fonts = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        persianFamily = pick(fonts, "Vazirmatn", "Vazir", "Vazir Code", "Sahel", "Shabnam", "Tahoma", "Dialog");
        String latinFamily = pick(fonts, "JetBrains Mono", "JetBrainsMono NF", "Consolas", "Monospaced");

        Preferences prefs = api.persistence().preferences();
        Integer savedDelta = prefs.getInteger(PREF_FONT);
        fontDelta = savedDelta == null ? 0 : Math.max(MIN_DELTA, Math.min(MAX_DELTA, savedDelta));
        latinFamilyName = latinFamily;
        Integer sReq = prefs.getInteger(PREF_FONT_REQ);
        Integer sResp = prefs.getInteger(PREF_FONT_RESP);
        reqDelta = sReq == null ? fontDelta : Math.max(MIN_DELTA, Math.min(MAX_DELTA, sReq));
        respDelta = sResp == null ? fontDelta : Math.max(MIN_DELTA, Math.min(MAX_DELTA, sResp));
        Boolean sLink = prefs.getBoolean(PREF_LINK);
        linkZoom = sLink != null && sLink;
        searchCase = Boolean.TRUE.equals(prefs.getBoolean(PREF_S_CASE));
        searchRegex = Boolean.TRUE.equals(prefs.getBoolean(PREF_S_REGEX));
        searchAuto = Boolean.TRUE.equals(prefs.getBoolean(PREF_S_AUTO));

        StyleConstants.setFontFamily(latinAttr, latinFamily);
        StyleConstants.setFontSize(latinAttr, BASE_LATIN + fontDelta);
        StyleConstants.setFontFamily(persianAttr, persianFamily);
        StyleConstants.setFontSize(persianAttr, BASE_PERSIAN + fontDelta);

        input = new JTextArea();
        input.setLineWrap(true);
        input.setFont(new Font(persianFamily, Font.PLAIN, BASE_INPUT + fontDelta));

        output = new JTextPane();
        output.setEditable(false);
        output.getDocument().putProperty("i18n", Boolean.TRUE);

        bindZoom(input);
        bindZoom(output);

        timer = new Timer(120, e -> render());
        timer.setRepeats(false);
        input.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { timer.restart(); }
            public void removeUpdate(DocumentEvent e) { timer.restart(); }
            public void changedUpdate(DocumentEvent e) { timer.restart(); }
        });

        prettyBox = new JCheckBox("Pretty JSON", true);
        rtlBox = new JCheckBox("RTL", false);
        digitsBox = new JCheckBox("Latin digits", false);
        prettyBox.addActionListener(e -> { render(); refreshEditors(); });
        digitsBox.addActionListener(e -> { render(); refreshEditors(); });
        rtlBox.addActionListener(e -> {
            output.setComponentOrientation(orientation());
            render();
            refreshEditors();
        });

        JButton pasteBtn = new JButton("Paste");
        pasteBtn.addActionListener(e -> {
            try {
                Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                input.setText((String) data);
            } catch (Exception ignored) {
            }
        });
        JButton copyBtn = new JButton("Copy result");
        copyBtn.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(lastPlain), null));
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> input.setText(""));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.add(pasteBtn);
        bar.add(copyBtn);
        bar.add(clearBtn);
        bar.add(prettyBox);
        bar.add(digitsBox);
        bar.add(rtlBox);
        bar.add(new JLabel("Zoom: Ctrl+Shift +/- (0 resets)"));

        String savedSend = prefs.getString(PREF_SEND);
        String savedPreview = prefs.getString(PREF_PREVIEW);
        sendKeyField = new JTextField(savedSend != null ? savedSend : DEFAULT_SEND, 14);
        previewKeyField = new JTextField(savedPreview != null ? savedPreview : DEFAULT_PREVIEW, 14);
        statusLabel = new JLabel(" ");
        JButton applyBtn = new JButton("Apply");
        applyBtn.addActionListener(e -> applyHotkeys());

        JPanel keyBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        keyBar.add(new JLabel("Send to tab hotkey:"));
        keyBar.add(sendKeyField);
        keyBar.add(new JLabel("Preview hotkey:"));
        keyBar.add(previewKeyField);
        keyBar.add(applyBtn);
        keyBar.add(statusLabel);

        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.NORTH);
        north.add(keyBar, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(input), new JScrollPane(output));
        split.setResizeWeight(0.25);

        root = new JPanel(new BorderLayout());
        root.add(north, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        api.userInterface().registerSuiteTab("Unicode/Persian", root);

        // Extra tab inside every Request / Response message editor (Proxy, Repeater, Logger, ...)
        api.userInterface().registerHttpResponseEditorProvider(ctx -> new DecodedEditor(false, isNested(ctx)));
        api.userInterface().registerHttpRequestEditorProvider(ctx -> new DecodedEditor(true, isNested(ctx)));
        log("editor tab providers registered (build with message-editor tab)");

        api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                if (event.messageEditorRequestResponse().isEmpty() && event.selectedRequestResponses().isEmpty()) {
                    return List.of();
                }
                final Component focus = event.messageEditorRequestResponse().isPresent()
                        ? KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
                        : null;
                JMenuItem toTab = new JMenuItem("Send to Unicode/Persian tab");
                toTab.addActionListener(e -> safe(() -> showInTab(resolveText(focus, event))));
                JMenuItem preview = new JMenuItem("Preview decoded");
                preview.addActionListener(e -> safe(() -> showPreview(resolveText(focus, event))));
                return List.of(toTab, preview);
            }
        });

        applyHotkeys();

        // Burp's Light/Dark switch changes the Look and Feel: restyle everything when that happens.
        themeTimer = new Timer(200, e -> onThemeChanged());
        themeTimer.setRepeats(false);
        lafListener = ev -> {
            if ("lookAndFeel".equals(ev.getPropertyName())) {
                themeTimer.restart();
            }
        };
        UIManager.addPropertyChangeListener(lafListener);

        api.extension().registerUnloadingHandler(() -> {
            timer.stop();
            themeTimer.stop();
            UIManager.removePropertyChangeListener(lafListener);
            deregisterHotkeys();
            SwingUtilities.invokeLater(() -> {
                if (previewFrame != null) {
                    previewFrame.dispose();
                }
            });
        });
    }

    // ---------- zoom ----------

    private void bindZoom(JComponent c) {
        bindZoom(c, this::zoom);
    }

    private void bindZoom(JComponent c, IntConsumer z) {
        int ctrl = InputEvent.CTRL_DOWN_MASK;
        int both = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;
        int[] plusKeys = {KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD};
        int[] minusKeys = {KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT};
        InputMap im = c.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = c.getActionMap();
        am.put("zoomIn", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { z.accept(1); }
        });
        am.put("zoomOut", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { z.accept(-1); }
        });
        am.put("zoomReset", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { z.accept(0); }
        });
        for (int k : plusKeys) {
            im.put(KeyStroke.getKeyStroke(k, both), "zoomIn");
            im.put(KeyStroke.getKeyStroke(k, ctrl), "zoomIn");
        }
        for (int k : minusKeys) {
            im.put(KeyStroke.getKeyStroke(k, both), "zoomOut");
            im.put(KeyStroke.getKeyStroke(k, ctrl), "zoomOut");
        }
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_0, both), "zoomReset");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_0, ctrl), "zoomReset");
    }

    private void zoom(int step) {
        int next = step == 0 ? 0 : Math.max(MIN_DELTA, Math.min(MAX_DELTA, fontDelta + step));
        if (next == fontDelta) {
            return;
        }
        fontDelta = next;
        api.persistence().preferences().setInteger(PREF_FONT, fontDelta);
        StyleConstants.setFontSize(latinAttr, BASE_LATIN + fontDelta);
        StyleConstants.setFontSize(persianAttr, BASE_PERSIAN + fontDelta);
        input.setFont(new Font(persianFamily, Font.PLAIN, BASE_INPUT + fontDelta));
        render();
        if (previewFrame != null && previewFrame.isVisible() && previewRaw != null) {
            renderTo(previewPane, previewRaw);
        }
        refreshEditors();
    }

    // ---------- hotkeys ----------

    private void deregisterHotkeys() {
        if (sendKeyReg != null) {
            sendKeyReg.deregister();
            sendKeyReg = null;
        }
        if (previewKeyReg != null) {
            previewKeyReg.deregister();
            previewKeyReg = null;
        }
    }

    private void applyHotkeys() {
        deregisterHotkeys();
        String sendKey = sendKeyField.getText().trim();
        String previewKey = previewKeyField.getText().trim();
        Preferences prefs = api.persistence().preferences();
        prefs.setString(PREF_SEND, sendKey);
        prefs.setString(PREF_PREVIEW, previewKey);
        try {
            if (!sendKey.isEmpty()) {
                sendKeyReg = api.userInterface().registerHotKeyHandler(
                        HotKeyContext.HTTP_MESSAGE_EDITOR, sendKey,
                        ev -> onHotkey(ev, true));
            }
            if (!previewKey.isEmpty()) {
                previewKeyReg = api.userInterface().registerHotKeyHandler(
                        HotKeyContext.HTTP_MESSAGE_EDITOR, previewKey,
                        ev -> onHotkey(ev, false));
            }
            statusLabel.setText("Hotkeys applied");
        } catch (Exception ex) {
            statusLabel.setText("Hotkey error: " + ex.getMessage());
        }
    }

    // ---------- actions ----------

    private void showInTab(String text) {
        if (text == null) {
            statusLabel.setText("Nothing captured (see Extensions > Output)");
            log("showInTab: text is null");
            return;
        }
        log("showInTab length = " + text.length());
        input.setText(text);
        timer.stop();
        render();
        selectOwnTab();
        output.requestFocusInWindow();
    }

    private void selectOwnTab() {
        try {
            JTabbedPane tp = (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, root);
            if (tp == null) {
                return;
            }
            Component c = root;
            while (c != null && c.getParent() != tp) {
                c = c.getParent();
            }
            if (c != null) {
                tp.setSelectedComponent(c);
            }
        } catch (Exception ignored) {
        }
    }

    private void showPreview(String text) {
        if (text == null) {
            statusLabel.setText("Nothing captured (see Extensions > Output)");
            log("showPreview: text is null");
            return;
        }
        log("showPreview length = " + text.length());
        if (previewFrame == null) {
            previewPane = new JTextPane();
            previewPane.setEditable(false);
            previewPane.getDocument().putProperty("i18n", Boolean.TRUE);
            bindZoom(previewPane);
            previewFrame = new JFrame("Decoded preview (Esc to close)");
            previewFrame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            previewFrame.add(new JScrollPane(previewPane));
            previewFrame.setSize(900, 600);
            previewFrame.setLocationRelativeTo(null);
            previewFrame.getRootPane().registerKeyboardAction(
                    e -> previewFrame.setVisible(false),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
            previewFrame.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentHidden(ComponentEvent e) {
                    previewPane.setText("");
                    previewRaw = null;
                }
            });
        }
        previewRaw = text;
        previewPane.setComponentOrientation(orientation());
        renderTo(previewPane, text);
        previewFrame.setVisible(true);
        previewFrame.toFront();
        previewPane.requestFocusInWindow();
    }

    private ComponentOrientation orientation() {
        return rtlBox.isSelected() ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT;
    }

    // ---------- extraction from Burp ----------

    private static String extractFromEvent(ContextMenuEvent event) {
        Optional<MessageEditorHttpRequestResponse> ed = event.messageEditorRequestResponse();
        if (ed.isPresent()) {
            return extract(ed.get());
        }
        List<HttpRequestResponse> list = event.selectedRequestResponses();
        if (!list.isEmpty() && list.get(0).hasResponse()) {
            return new String(list.get(0).response().toByteArray().getBytes(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String extract(MessageEditorHttpRequestResponse m) {
        boolean isResp = m.selectionContext() == MessageEditorHttpRequestResponse.SelectionContext.RESPONSE;
        if (isResp && !m.requestResponse().hasResponse()) {
            return null;
        }
        ByteArray whole = isResp
                ? m.requestResponse().response().toByteArray()
                : m.requestResponse().request().toByteArray();
        String full = new String(whole.getBytes(), StandardCharsets.UTF_8);
        Optional<Range> sel = m.selectionOffsets();
        if (sel.isPresent()) {
            int s = sel.get().startIndexInclusive();
            int e = sel.get().endIndexExclusive();
            // Burp reports offsets in characters of the decoded text, not bytes.
            if (s >= 0 && s < e && e <= full.length()) {
                return full.substring(s, e);
            }
        }
        return full;
    }

    // ---------- selection from the focused editor ----------

    private void onHotkey(HotKeyEvent ev, boolean toTab) {
        final String fallback = ev.messageEditorRequestResponse().map(DecoderExtension::extract).orElse(null);
        SwingUtilities.invokeLater(() -> safe(() -> {
            String text = selectedFromFocus(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
            if (text == null) {
                text = fallback;
                log("hotkey fallback extract, length=" + (text == null ? "null" : text.length()));
            }
            if (toTab) {
                showInTab(text);
            } else {
                showPreview(text);
            }
        }));
    }

    private String resolveText(Component focus, ContextMenuEvent event) {
        String s = selectedFromFocus(focus);
        if (s != null) {
            return s;
        }
        String f = extractFromEvent(event);
        log("menu fallback extract, length=" + (f == null ? "null" : f.length()));
        return f;
    }

    private void log(String m) {
        try {
            api.logging().logToOutput("[viewer] " + m);
        } catch (Throwable ignored) {
        }
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            try {
                api.logging().logToError(t);
            } catch (Throwable ignored) {
            }
            statusLabel.setText("Error: " + t);
        }
    }

    private static final String SENTINEL = "__decoder_ext_sentinel_9f3a1c__";

    // Must run on the EDT. Returns null when nothing is selected or it cannot be read.
    private String selectedFromFocus(Component focus) {
        log("focus owner = " + (focus == null ? "null" : focus.getClass().getName()));
        if (focus == null) {
            return null;
        }
        if (focus instanceof JTextComponent) {
            String sel = ((JTextComponent) focus).getSelectedText();
            log("JTextComponent selection length = " + (sel == null ? "null" : sel.length()));
            if (sel != null && !sel.isEmpty()) {
                return sel;
            }
        }
        if (!(focus instanceof JComponent) || ((JComponent) focus).getTransferHandler() == null) {
            log("no TransferHandler on focus owner");
            return null;
        }
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        String old = readClip(cb);
        try {
            cb.setContents(new StringSelection(SENTINEL), null);
            TransferHandler.getCopyAction().actionPerformed(
                    new ActionEvent(focus, ActionEvent.ACTION_PERFORMED, "copy"));
            String now = readClip(cb);
            log("copy action result length = " + (now == null ? "null" : (now.equals(SENTINEL) ? "sentinel(unchanged)" : now.length())));
            if (now == null || now.isEmpty() || now.equals(SENTINEL)) {
                return null;
            }
            return now;
        } catch (Exception ex) {
            return null;
        } finally {
            if (old != null) {
                try {
                    cb.setContents(new StringSelection(old), null);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String readClip(Clipboard cb) {
        try {
            return (String) cb.getData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- syntax colors (headers + JSON) ----------

    private static SimpleAttributeSet fg(Color c) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, c);
        return a;
    }

    private static final Pattern FORM_PAIR = Pattern.compile("([^&=\\s]+)=([^&\\s]*)");

    // Comments, doctype, tags, attribute names and values. <script> bodies get JS colors, <style> stays plain.
    private static final Pattern HTML_TOKEN = Pattern.compile(
            "<!--.*?-->|<![A-Za-z][^>]*>|<(/?)([A-Za-z][\\w:.-]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*?)(/?)>",
            Pattern.DOTALL);
    private static final Pattern HTML_ATTR = Pattern.compile(
            "([^\\s=/\"'<>]+)(?:(\\s*=\\s*)(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+))?");

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int n = needle.length();
        for (int i = from; i + n <= s.length(); i++) {
            if (s.regionMatches(true, i, needle, 0, n)) {
                return i;
            }
        }
        return -1;
    }

    // Small JavaScript highlighter: keywords, strings/templates, numbers, regex literals, comments.
    private static final Set<String> JS_KEYWORDS = new HashSet<>(Arrays.asList(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do",
            "else", "export", "extends", "finally", "for", "function", "if", "import", "in", "instanceof",
            "let", "new", "of", "return", "super", "switch", "this", "throw", "try", "typeof", "var", "void",
            "while", "with", "yield", "async", "await", "static", "null", "true", "false", "undefined",
            "NaN", "Infinity"));

    private static boolean regexAllowed(char prev, String prevWord) {
        if (!prevWord.isEmpty()) {
            return prevWord.equals("return") || prevWord.equals("typeof") || prevWord.equals("case")
                    || prevWord.equals("in") || prevWord.equals("of") || prevWord.equals("void")
                    || prevWord.equals("delete") || prevWord.equals("throw") || prevWord.equals("else")
                    || prevWord.equals("do");
        }
        return prev == 0 || "(,=:[!&|?{};+-*%<>~^".indexOf(prev) >= 0;
    }

    private static void colorizeJs(StyledDocument doc, String s, int from, int to,
                                   AttributeSet kw, AttributeSet str, AttributeSet num, AttributeSet cmt) {
        int i = from;
        char prev = 0;           // last significant character
        String prevWord = "";    // last identifier (tells "return /re/" from "a / b")
        while (i < to) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (c == '/' && i + 1 < to && s.charAt(i + 1) == '/') {
                while (i < to && s.charAt(i) != '\n') {
                    i++;
                }
                doc.setCharacterAttributes(start, i - start, cmt, false);
                continue;
            }
            if (c == '/' && i + 1 < to && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = (e < 0 || e + 2 > to) ? to : e + 2;
                doc.setCharacterAttributes(start, i - start, cmt, false);
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                i++;
                while (i < to) {
                    char d = s.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == c) {
                        i++;
                        break;
                    }
                    if (d == '\n' && c != '`') {
                        break;
                    }
                    i++;
                }
                i = Math.min(i, to);
                doc.setCharacterAttributes(start, i - start, str, false);
                prev = c;
                prevWord = "";
                continue;
            }
            if (c == '/' && regexAllowed(prev, prevWord)) {
                int j = i + 1;
                boolean cls = false;
                boolean ok = false;
                while (j < to) {
                    char d = s.charAt(j);
                    if (d == '\n') {
                        break;
                    }
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '[') {
                        cls = true;
                    } else if (d == ']') {
                        cls = false;
                    } else if (d == '/' && !cls) {
                        ok = true;
                        j++;
                        break;
                    }
                    j++;
                }
                if (ok) {
                    while (j < to && Character.isLetter(s.charAt(j))) {
                        j++;
                    }
                    doc.setCharacterAttributes(start, j - start, str, false);
                    i = j;
                    prev = ')';
                    prevWord = "";
                    continue;
                }
            }
            boolean afterValue = !prevWord.isEmpty() || prev == ')' || prev == ']';
            if (Character.isDigit(c)
                    || (c == '.' && i + 1 < to && Character.isDigit(s.charAt(i + 1)) && !afterValue)) {
                i++;
                while (i < to) {
                    char d = s.charAt(i);
                    char p = s.charAt(i - 1);
                    if (Character.isLetterOrDigit(d) || d == '.' || d == '_'
                            || ((d == '+' || d == '-') && (p == 'e' || p == 'E'))) {
                        i++;
                    } else {
                        break;
                    }
                }
                doc.setCharacterAttributes(start, i - start, num, false);
                prev = '0';
                prevWord = "";
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                i++;
                while (i < to && Character.isJavaIdentifierPart(s.charAt(i))) {
                    i++;
                }
                String w = s.substring(start, i);
                if (prev != '.' && JS_KEYWORDS.contains(w)) {
                    doc.setCharacterAttributes(start, i - start, kw, false);
                }
                prev = 'a';
                prevWord = w;
                continue;
            }
            prev = c;
            prevWord = "";
            i++;
        }
    }

    private static boolean isJsScript(String attrs) {
        String a = attrs.toLowerCase();
        return !a.contains("type") || a.contains("javascript") || a.contains("ecmascript") || a.contains("module");
    }

    private static void colorizeHtml(StyledDocument doc, String s, int from,
                                     AttributeSet tag, AttributeSet attrName, AttributeSet attrVal,
                                     AttributeSet comment, AttributeSet decl,
                                     AttributeSet kw, AttributeSet num) {
        Matcher m = HTML_TOKEN.matcher(s);
        int pos = from;
        while (pos < s.length() && m.find(pos)) {
            int ms = m.start();
            int me = m.end();
            if (s.charAt(ms + 1) == '!') {
                doc.setCharacterAttributes(ms, me - ms, s.startsWith("<!--", ms) ? comment : decl, false);
                pos = me;
                continue;
            }
            doc.setCharacterAttributes(ms, m.end(2) - ms, tag, false); // "<" or "</" plus the tag name
            int as = m.start(3);
            int ae = m.end(3);
            if (ae > as) {
                Matcher am = HTML_ATTR.matcher(s);
                am.region(as, ae);
                while (am.find()) {
                    doc.setCharacterAttributes(am.start(1), am.end(1) - am.start(1), attrName, false);
                    if (am.group(3) != null) {
                        doc.setCharacterAttributes(am.start(3), am.end(3) - am.start(3), attrVal, false);
                    }
                }
            }
            doc.setCharacterAttributes(m.start(4), me - m.start(4), tag, false); // ">" or "/>"
            pos = me;
            if (m.group(1).isEmpty()) {
                String name = m.group(2).toLowerCase();
                if (name.equals("script") || name.equals("style")) {
                    int close = indexOfIgnoreCase(s, "</" + name, me);
                    if (close > 0) {
                        if (name.equals("script") && isJsScript(m.group(3))) {
                            colorizeJs(doc, s, me, close, kw, attrVal, num, comment);
                        }
                        pos = close;
                    }
                }
            }
        }
    }


    // Colors sampled from Burp's own editor (dark theme); light theme is an approximation.
    private static final class Pal {
        Color bg, text, head, key, str, num, lit, cname, cval, cmt, bandOverlay;
        Color hitFill, hitBorder, curFill, curBorder, gutterText, gutterCur;
    }

    private boolean burpDark() {
        // The live Look and Feel color is what is really on screen, so prefer it over Burp's theme setting.
        Color c = UIManager.getColor("Panel.background");
        if (c != null) {
            return isDark(c);
        }
        try {
            return api.userInterface().currentTheme() == Theme.DARK;
        } catch (Throwable t) {
            return true;
        }
    }

    private Pal palette() {
        Pal p = new Pal();
        if (burpDark()) {
            p.bg = new Color(0x2B2B2B);
            p.text = new Color(0xBABABA);
            p.head = new Color(0xD1E8F9);
            p.key = new Color(0xE9C063);
            p.str = new Color(0x93C763);
            p.num = new Color(0x79C1F4);
            p.lit = new Color(0xFF9E57);
            p.cname = new Color(0xBBCDFF);
            p.cval = new Color(0xA5C35B);
            p.cmt = new Color(0x808080);
            p.bandOverlay = new Color(110, 130, 140, 38);
            p.hitFill = new Color(0x111B4F);
            p.hitBorder = new Color(0xA0A4B9);
            p.curFill = new Color(0xDB612F);
            p.curBorder = new Color(0xFFAA78);
            p.gutterText = new Color(0xA0A0A0);
            p.gutterCur = new Color(0xD1E8F9);
        } else {
            p.bg = Color.WHITE;
            p.text = new Color(0x2B2B2B);
            p.head = new Color(0x005A9E);
            p.key = new Color(0x9A6700);
            p.str = new Color(0x2E7D12);
            p.num = new Color(0x0B63B5);
            p.lit = new Color(0xC2570C);
            p.cname = new Color(0x3949AB);
            p.cval = new Color(0x4E7A12);
            p.cmt = new Color(0x8C8C8C);
            p.bandOverlay = new Color(0, 50, 120, 14);
            p.hitFill = new Color(0xD2E0FF);
            p.hitBorder = new Color(0x788CD2);
            p.curFill = new Color(0xFFAA64);
            p.curBorder = new Color(0xD26E1E);
            p.gutterText = new Color(0x999999);
            p.gutterCur = new Color(0x333333);
        }
        return p;
    }

    private void applyTheme(JTextPane pane) {
        Pal p = palette();
        pane.setBackground(p.bg);
        pane.setForeground(p.text);
        pane.setCaretColor(burpDark() ? Color.WHITE : Color.BLACK);
        // null = keep the syntax colors on selected text (like Burp) instead of forcing it all white
        pane.setSelectedTextColor(null);
    }

    private void colorize(JTextPane pane, StyledDocument doc, String s) {
        if (s.isEmpty() || s.length() > COLOR_LIMIT) {
            return;
        }
        Pal pal = palette();
        SimpleAttributeSet head = fg(pal.head);
        SimpleAttributeSet key = fg(pal.key);
        SimpleAttributeSet str = fg(pal.str);
        SimpleAttributeSet num = fg(pal.num);
        SimpleAttributeSet lit = fg(pal.lit);
        SimpleAttributeSet cname = fg(pal.cname);
        SimpleAttributeSet cval = fg(pal.cval);
        SimpleAttributeSet cmt = fg(pal.cmt);

        int bodyStart = 0;
        boolean form = false;
        boolean js = false;
        int firstNl = s.indexOf('\n');
        String first = firstNl < 0 ? s : s.substring(0, firstNl);
        if (first.contains("HTTP/")) {
            int firstEnd = firstNl < 0 ? s.length() : firstNl;
            doc.setCharacterAttributes(0, firstEnd, head, false);
            int sep = s.indexOf("\n\n");
            int headEnd = sep < 0 ? s.length() : sep;
            bodyStart = sep < 0 ? s.length() : sep + 2;
            String hl = s.substring(0, headEnd).toLowerCase();
            form = hl.contains("x-www-form-urlencoded");
            int cti = hl.indexOf("content-type:");
            if (cti >= 0) {
                int cte = hl.indexOf('\n', cti);
                String ct = hl.substring(cti, cte < 0 ? hl.length() : cte);
                js = ct.contains("javascript") || ct.contains("ecmascript");
            }
            int pos = firstNl < 0 ? s.length() : firstNl + 1;
            while (pos < headEnd) {
                int eol = s.indexOf('\n', pos);
                if (eol < 0 || eol > headEnd) {
                    eol = headEnd;
                }
                int colon = s.indexOf(':', pos);
                if (colon > pos && colon < eol) {
                    doc.setCharacterAttributes(pos, colon + 1 - pos, head, false);
                    String name = s.substring(pos, colon).trim().toLowerCase();
                    if (name.equals("cookie") || name.equals("set-cookie")) {
                        int seg = colon + 1;
                        while (seg < eol) {
                            int end = s.indexOf(';', seg);
                            if (end < 0 || end > eol) {
                                end = eol;
                            }
                            int eq = s.indexOf('=', seg);
                            if (eq > seg && eq < end) {
                                int ns = seg;
                                while (ns < eq && s.charAt(ns) == ' ') {
                                    ns++;
                                }
                                doc.setCharacterAttributes(ns, eq - ns, cname, false);
                                doc.setCharacterAttributes(eq + 1, end - eq - 1, cval, false);
                            }
                            seg = end + 1;
                        }
                    }
                }
                pos = eol + 1;
            }
        }
        int i = bodyStart;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i >= s.length()) {
            return;
        }
        char lead = s.charAt(i);
        if (js) {
            colorizeJs(doc, s, i, s.length(), lit, str, num, cmt);
        } else if (lead == '{' || lead == '[') {
            Matcher m = TOKEN.matcher(s);
            m.region(bodyStart, s.length());
            while (m.find()) {
                if (m.group(1) != null) {
                    doc.setCharacterAttributes(m.start(), m.start(1) - m.start(), key, false);
                    continue;
                }
                char c = s.charAt(m.start());
                SimpleAttributeSet a = c == '"' ? str : (c == '-' || Character.isDigit(c)) ? num : lit;
                doc.setCharacterAttributes(m.start(), m.end() - m.start(), a, false);
            }
        } else if (lead == '<') {
            colorizeHtml(doc, s, i, key, cname, str, cmt, head, lit, num);
        } else if (form) {
            Matcher m = FORM_PAIR.matcher(s);
            m.region(bodyStart, s.length());
            while (m.find()) {
                doc.setCharacterAttributes(m.start(1), m.end(1) - m.start(1), cname, false);
                doc.setCharacterAttributes(m.start(2), m.end(2) - m.start(2), cval, false);
            }
        }
    }

    // ---------- tab inside Burp's Request / Response message editors ----------

    private final Set<DecodedEditor> editors = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    // "Burp native view" creates a full Burp editor, and Burp adds every extension tab to it,
    // including ours, which would create another native editor, and so on (tabs inside tabs).
    // So our tab stays disabled inside editors that an extension created.
    private void onThemeChanged() {
        safe(() -> {
            render();
            if (previewFrame != null && previewFrame.isVisible() && previewRaw != null) {
                renderTo(previewPane, previewRaw);
            }
            refreshEditors();
        });
    }

    private volatile boolean buildingNative;

    private boolean isNested(EditorCreationContext ctx) {
        if (buildingNative) {
            return true;
        }
        try {
            return ctx.toolSource().isFromTool(ToolType.EXTENSIONS);
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshEditors() {
        List<DecodedEditor> copy;
        synchronized (editors) {
            copy = new ArrayList<>(editors);
        }
        for (DecodedEditor e : copy) {
            e.onSettingsChanged();
        }
    }

    // Cheap pre-check so the tab only shows up when there is something to decode:
    // a \\u escape, or raw UTF-8 Arabic/Persian bytes.
    private static void flat(AbstractButton b) {
        b.putClientProperty("JButton.buttonType", "toolBarButton"); // FlatLaf (Burp's look and feel)
        b.setFocusable(false);
        b.setMargin(new Insets(2, 8, 2, 8));
    }

    private Color lineColor() {
        Color c = UIManager.getColor("Component.borderColor");
        if (c == null) {
            c = UIManager.getColor("Separator.foreground");
        }
        if (c == null) {
            c = burpDark() ? new Color(0x4A4A4A) : new Color(0xD0D0D0);
        }
        return c;
    }

    private static int countEscapes(String s) {
        int n = 0;
        for (int i = 0; i + 5 < s.length(); i++) {
            if (s.charAt(i) == 92 && s.charAt(i + 1) == 'u' && hex4(s, i + 2) >= 0) {
                n++;
                i += 5;
            }
        }
        return n;
    }

    // ---------- vector icons for the search bar ----------
    // Drawn with Java2D instead of unicode glyphs: they look the same on every font/OS,
    // stay crisp at any DPI, and follow the button's foreground (so dark/light theme just works).

    private enum IconKind { GEAR, ARROW_LEFT, ARROW_RIGHT, CLEAR, HELP }

    private static final class VIcon implements Icon {
        private final IconKind kind;
        private final int size;

        VIcon(IconKind kind, int size) {
            this.kind = kind;
            this.size = size;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        private static Color alpha(Color c, int a) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                Color fg = c != null ? c.getForeground() : Color.GRAY;
                if (c != null && !c.isEnabled()) {
                    fg = alpha(fg, 90);
                } else if (kind == IconKind.CLEAR) {
                    boolean hot = c instanceof AbstractButton && ((AbstractButton) c).getModel().isRollover();
                    fg = alpha(fg, hot ? 255 : 150);
                }
                g.setColor(fg);
                g.translate(x, y);
                double sc = size / 16.0;
                g.scale(sc, sc); // everything below is designed on a 16x16 grid
                switch (kind) {
                    case GEAR: {
                        int teeth = 8;
                        double step = Math.PI * 2 / teeth;
                        Path2D.Double p = new Path2D.Double();
                        for (int t = 0; t < teeth; t++) {
                            double c0 = t * step;
                            double[] ang = {c0 - 0.30 * step, c0 - 0.16 * step, c0 + 0.16 * step, c0 + 0.30 * step};
                            double[] rad = {5.3, 7.0, 7.0, 5.3};
                            for (int k = 0; k < 4; k++) {
                                double px = 8 + rad[k] * Math.cos(ang[k]);
                                double py = 8 + rad[k] * Math.sin(ang[k]);
                                if (t == 0 && k == 0) {
                                    p.moveTo(px, py);
                                } else {
                                    p.lineTo(px, py);
                                }
                            }
                        }
                        p.closePath();
                        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.draw(p);
                        g.draw(new Ellipse2D.Double(5.6, 5.6, 4.8, 4.8));
                        break;
                    }
                    case ARROW_LEFT:
                    case ARROW_RIGHT: {
                        boolean left = kind == IconKind.ARROW_LEFT;
                        double tail = left ? 13 : 3;
                        double tip = left ? 3 : 13;
                        double wing = left ? 7.5 : 8.5;
                        Path2D.Double p = new Path2D.Double();
                        p.moveTo(tail, 8);
                        p.lineTo(tip, 8);
                        p.moveTo(wing, 3.5);
                        p.lineTo(tip, 8);
                        p.lineTo(wing, 12.5);
                        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.draw(p);
                        break;
                    }
                    case CLEAR: {
                        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.draw(new java.awt.geom.Line2D.Double(4.5, 4.5, 11.5, 11.5));
                        g.draw(new java.awt.geom.Line2D.Double(11.5, 4.5, 4.5, 11.5));
                        break;
                    }
                    case HELP: {
                        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.draw(new Ellipse2D.Double(1.5, 1.5, 13, 13));
                        Path2D.Double q = new Path2D.Double();
                        q.moveTo(6.1, 6.3);
                        q.curveTo(6.1, 4.6, 9.9, 4.6, 9.9, 6.4);
                        q.curveTo(9.9, 7.6, 8.0, 7.7, 8.0, 9.1);
                        g.draw(q);
                        g.fill(new Ellipse2D.Double(7.2, 10.5, 1.6, 1.6));
                        break;
                    }
                    default:
                        break;
                }
            } finally {
                g.dispose();
            }
        }
    }

    // bordered = square button with Burp/FlatLaf border (the < > buttons)
    // not bordered = flat toolbar-style icon (the gear and ? buttons)
    private static JButton iconButton(IconKind kind, String tip, boolean bordered) {
        Icon icon = new VIcon(kind, 16);
        JButton b = new JButton(icon);
        b.setDisabledIcon(icon);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setMargin(new Insets(2, 2, 2, 2));
        b.setAlignmentY(Component.CENTER_ALIGNMENT);
        if (!bordered) {
            b.putClientProperty("JButton.buttonType", "toolBarButton"); // FlatLaf (Burp's look and feel)
        }
        Dimension d = bordered ? new Dimension(32, 26) : new Dimension(26, 26);
        b.setPreferredSize(d);
        b.setMinimumSize(d);
        b.setMaximumSize(d);
        return b;
    }

    // Search field styled like Burp's: rounded border from the Look and Feel colors,
    // accent border while focused, red border on a bad regex, clear (x) inside on the right.
    private final class SearchBox extends JPanel {
        private boolean focused;
        private boolean error;

        SearchBox(JTextField field, JComponent trailing) {
            super(new BorderLayout(0, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 3));
            field.setOpaque(false);
            field.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 4));
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    focused = true;
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    focused = false;
                    repaint();
                }
            });
            add(field, BorderLayout.CENTER);
            add(trailing, BorderLayout.EAST);
        }

        void setError(boolean e) {
            if (error != e) {
                error = e;
                repaint();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(d.width, Math.max(d.height, 26));
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(60, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                int w = getWidth();
                int h = getHeight();
                Color bg = UIManager.getColor("TextField.background");
                if (bg == null) {
                    bg = burpDark() ? new Color(0x2B2B2B) : Color.WHITE;
                }
                int arc = Math.max(6, UIManager.getInt("TextComponent.arc"));
                g.setColor(bg);
                g.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);

                Color border;
                float bw = 1f;
                if (error) {
                    border = new Color(0xE06C6C);
                    bw = 1.5f;
                } else if (focused) {
                    border = UIManager.getColor("Component.focusedBorderColor");
                    if (border == null) {
                        border = UIManager.getColor("Component.focusColor");
                    }
                    if (border == null) {
                        border = new Color(0x4A88C7);
                    }
                    bw = 1.5f;
                } else {
                    border = lineColor();
                }
                g.setColor(border);
                g.setStroke(new BasicStroke(bw));
                g.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            } finally {
                g.dispose();
            }
        }
    }

    // Rounded, bordered match box like Burp's search hits.
    private static final class BoxPainter implements Highlighter.HighlightPainter {
        volatile Color fill = new Color(0x111B4F);
        volatile Color border = new Color(0xA0A4B9);

        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                java.awt.geom.Rectangle2D a = c.getUI().modelToView2D(c, p0, Position.Bias.Forward);
                java.awt.geom.Rectangle2D b = c.getUI().modelToView2D(c, p1, Position.Bias.Backward);
                if (a == null || b == null) {
                    return;
                }
                if (Math.abs(a.getY() - b.getY()) > 1) { // match wraps over several rows
                    Rectangle bb = bounds.getBounds();
                    g.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 120));
                    g.fillRect(bb.x, (int) a.getY(), bb.width, (int) (b.getY() + b.getHeight() - a.getY()));
                    return;
                }
                int x = (int) Math.min(a.getX(), b.getX());
                int w = Math.max(3, (int) Math.abs(b.getX() - a.getX()));
                int y = (int) a.getY();
                int h = (int) a.getHeight() - 1;
                g.setColor(fill);
                g.fillRoundRect(x, y, w, h, 4, 4);
                g.setColor(border);
                g.drawRoundRect(x, y, w, h, 4, 4);
            } catch (BadLocationException ignored) {
            }
        }
    }

    private void refreshSearches() {
        List<DecodedEditor> copy;
        synchronized (editors) {
            copy = new ArrayList<>(editors);
        }
        for (DecodedEditor e : copy) {
            e.syncSearchOptions();
        }
    }

    private static boolean needsDecode(byte[] b) {
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            if (v == '\\' && i + 1 < b.length && b[i + 1] == 'u') {
                return true;
            }
            if (v >= 0xD8 && v <= 0xDB) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDark(Color bg) {
        return (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000 < 128;
    }

    // Visible 2px caret that is also shown in read-only text panes.
    private static final class ThickCaret extends DefaultCaret {
        ThickCaret() {
            setBlinkRate(530);
        }

        @Override
        public void focusGained(java.awt.event.FocusEvent e) {
            super.focusGained(e);
            setVisible(true);
        }

        @Override
        protected synchronized void damage(Rectangle r) {
            if (r == null) {
                return;
            }
            x = r.x - 1;
            y = r.y;
            width = 5;
            height = r.height;
            repaint();
        }

        @Override
        public void paint(Graphics g) {
            if (!isVisible()) {
                return;
            }
            try {
                JTextComponent c = getComponent();
                java.awt.geom.Rectangle2D r = c.getUI().modelToView2D(c, getDot(), Position.Bias.Forward);
                if (r == null) {
                    return;
                }
                g.setColor(c.getCaretColor());
                g.fillRect((int) r.getX(), (int) r.getY(), 3, (int) r.getHeight());
            } catch (BadLocationException ignored) {
            }
        }
    }

    // Text pane that tints the caret's line, like Burp's editor does.
    private final class BandPane extends JTextPane {
        BandPane() {
            addCaretListener(e -> repaint());
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            try {
                if (getDocument().getLength() > 0) {
                    java.awt.geom.Rectangle2D r = modelToView2D(getCaretPosition());
                    if (r != null) {
                        g.setColor(palette().bandOverlay);
                        g.fillRect(0, (int) r.getY(), getWidth(), (int) r.getHeight());
                    }
                }
            } catch (BadLocationException ignored) {
            }
        }
    }

    private final class DecodedEditor implements ExtensionProvidedHttpRequestEditor, ExtensionProvidedHttpResponseEditor {
        private final boolean request;
        private final boolean nested;
        private boolean ready;
        private JPanel topBar;
        private final JPanel panel = new JPanel(new BorderLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                if (ready && themeTimer != null) {
                    themeTimer.restart();
                }
            }
        };
        private final JTextPane pane = new BandPane();
        private final CardLayout cards = new CardLayout();
        private final JPanel center = new JPanel(cards);
        private final JCheckBox nativeBox = new JCheckBox("Burp native view");
        private final JTextField search = new JTextField(16);
        private final JLabel count = new JLabel("0 matches");
        private final JCheckBox linkBox = new JCheckBox("Link zoom (Req+Resp)");
        private final JPanel searchBar = new JPanel(new BorderLayout(6, 0));
        private final List<int[]> hits = new ArrayList<>();
        private final BoxPainter painter = new BoxPainter();
        private final BoxPainter currentPainter = new BoxPainter();
        private final JLabel escLabel = new JLabel(" ");
        private Gutter gutter;
        private Object currentTag;
        private JButton clearBtn;
        private SearchBox box;
        private JCheckBoxMenuItem caseItem;
        private JCheckBoxMenuItem regexItem;
        private JCheckBoxMenuItem autoItem;
        private Color searchFg;
        private HttpRequestEditor nativeReq;
        private HttpResponseEditor nativeResp;
        private HttpRequestResponse current;
        private String raw = "";
        private String plain = "";
        private int hitIndex = -1;
        private boolean dirty;

        DecodedEditor(boolean request, boolean nested) {
            this.request = request;
            this.nested = nested;
            pane.setEditable(false);
            pane.getDocument().putProperty("i18n", Boolean.TRUE);
            pane.setComponentOrientation(orientation());
            bindZoom(pane, this::zoomBy);
            pane.getInputMap(JComponent.WHEN_FOCUSED).put(
                    KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "find");
            pane.getActionMap().put("find", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    search.requestFocusInWindow();
                    search.selectAll();
                }
            });
            final JScrollPane sp = new JScrollPane(pane);
            sp.setBorder(BorderFactory.createEmptyBorder());
            pane.setMargin(new Insets(6, 8, 6, 8));
            gutter = new Gutter();
            sp.setRowHeaderView(gutter);
            pane.setCaret(new ThickCaret());
            boolean dark = isDark(pane.getBackground());
            pane.setCaretColor(dark ? Color.WHITE : Color.BLACK);
            pane.addMouseWheelListener(e -> {
                if (e.isControlDown()) {
                    zoomBy(e.getWheelRotation() < 0 ? 1 : -1);
                    e.consume();
                } else {
                    sp.dispatchEvent(SwingUtilities.convertMouseEvent(pane, e, sp));
                }
            });
            center.add(sp, "text");

            JButton prev = iconButton(IconKind.ARROW_LEFT, "Previous match", true);
            JButton next = iconButton(IconKind.ARROW_RIGHT, "Next match (Enter)", true);
            search.putClientProperty("JTextField.placeholderText", "Search decoded text");
            search.setToolTipText("Search (Ctrl+F)");
            prev.addActionListener(e -> step(-1));
            next.addActionListener(e -> step(1));
            search.addActionListener(e -> step(1));
            search.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { runSearch(); }
                public void removeUpdate(DocumentEvent e) { runSearch(); }
                public void changedUpdate(DocumentEvent e) { runSearch(); }
            });
            // clear (x) button drawn inside the search box
            searchFg = search.getForeground();
            clearBtn = new JButton(new VIcon(IconKind.CLEAR, 14));
            clearBtn.setBorderPainted(false);
            clearBtn.setContentAreaFilled(false);
            clearBtn.setFocusPainted(false);
            clearBtn.setFocusable(false);
            clearBtn.setMargin(new Insets(0, 0, 0, 0));
            clearBtn.setPreferredSize(new Dimension(24, 22));
            clearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            clearBtn.setToolTipText("Clear search");
            clearBtn.setVisible(false);
            clearBtn.addActionListener(e -> {
                search.setText("");
                search.requestFocusInWindow();
            });
            box = new SearchBox(search, clearBtn);

            // gear menu: Case sensitive / Regex / Auto-scroll
            caseItem = new JCheckBoxMenuItem("Case sensitive", searchCase);
            regexItem = new JCheckBoxMenuItem("Regex", searchRegex);
            autoItem = new JCheckBoxMenuItem("Auto-scroll to match when text changes", searchAuto);
            ActionListener optionsChanged = e -> {
                searchCase = caseItem.isSelected();
                searchRegex = regexItem.isSelected();
                searchAuto = autoItem.isSelected();
                Preferences pr = api.persistence().preferences();
                pr.setBoolean(PREF_S_CASE, searchCase);
                pr.setBoolean(PREF_S_REGEX, searchRegex);
                pr.setBoolean(PREF_S_AUTO, searchAuto);
                refreshSearches();
            };
            caseItem.addActionListener(optionsChanged);
            regexItem.addActionListener(optionsChanged);
            autoItem.addActionListener(optionsChanged);
            JPopupMenu optionsMenu = new JPopupMenu();
            optionsMenu.add(caseItem);
            optionsMenu.add(regexItem);
            optionsMenu.add(autoItem);
            JButton gear = iconButton(IconKind.GEAR, "Search options", false);
            gear.addActionListener(e -> optionsMenu.show(gear, 0, -optionsMenu.getPreferredSize().height));

            JButton help = iconButton(IconKind.HELP, "Search help", false);
            JPopupMenu helpMenu = new JPopupMenu();
            JLabel helpText = new JLabel("<html>Ctrl+F &nbsp; focus the search box<br>"
                    + "Enter &nbsp; next match<br>"
                    + "Gear &nbsp; case sensitive / regex<br>"
                    + "Ctrl+wheel &nbsp; zoom</html>");
            helpText.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            helpMenu.add(helpText);
            help.addActionListener(e -> helpMenu.show(help, 0, -helpMenu.getPreferredSize().height));

            // same order as Burp's own search bar: [?] [gear] [<] [>] [ field  x ] [N matches]
            JPanel lead = new JPanel();
            lead.setLayout(new BoxLayout(lead, BoxLayout.X_AXIS));
            lead.add(help);
            lead.add(Box.createHorizontalStrut(2));
            lead.add(gear);
            lead.add(Box.createHorizontalStrut(8));
            lead.add(prev);
            lead.add(Box.createHorizontalStrut(4));
            lead.add(next);
            count.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 2));
            int countW = count.getFontMetrics(count.getFont()).stringWidth("5000 / 5000 matches") + 10;
            count.setPreferredSize(new Dimension(countW, 24));
            searchBar.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, lineColor()),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)));
            searchBar.add(lead, BorderLayout.WEST);
            searchBar.add(box, BorderLayout.CENTER);
            searchBar.add(count, BorderLayout.EAST);

            JButton copy = new JButton("Copy decoded");
            copy.setToolTipText("Copy the decoded text to the clipboard");
            copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(plain), null));
            nativeBox.setToolTipText("Show the decoded text inside Burp's own editor "
                    + "(Burp syntax highlighting + search bar; Persian bidi looks worse there)");
            nativeBox.addActionListener(e -> toggleNative());

            JButton smaller = new JButton("A-");
            JButton bigger = new JButton("A+");
            smaller.setToolTipText("Smaller font (Ctrl+mouse wheel also works)");
            bigger.setToolTipText("Bigger font (Ctrl+mouse wheel also works)");
            smaller.addActionListener(e -> zoomBy(-1));
            bigger.addActionListener(e -> zoomBy(1));
            flat(copy);
            flat(smaller);
            flat(bigger);
            linkBox.setSelected(linkZoom);
            linkBox.setToolTipText("When ticked, Request and Response tabs (and the main tab) zoom together");
            linkBox.addActionListener(e -> setLinkZoom(linkBox.isSelected()));

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
            left.add(nativeBox);
            left.add(vsep());
            left.add(copy);
            left.add(vsep());
            left.add(smaller);
            left.add(bigger);
            left.add(linkBox);
            escLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
            JPanel bar = new JPanel(new BorderLayout());
            topBar = bar;
            bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, lineColor()));
            bar.add(left, BorderLayout.WEST);
            bar.add(escLabel, BorderLayout.EAST);

            panel.add(bar, BorderLayout.NORTH);
            panel.add(searchBar, BorderLayout.SOUTH);
            panel.add(center, BorderLayout.CENTER);
            panel.addHierarchyListener(e -> {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && panel.isShowing() && dirty) {
                    refresh();
                }
            });
            editors.add(this);
            ready = true;
        }

        // ---- data in ----

        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            current = requestResponse;
            byte[] b = new byte[0];
            if (request) {
                b = requestResponse.request().toByteArray().getBytes();
            } else if (requestResponse.hasResponse()) {
                b = requestResponse.response().toByteArray().getBytes();
            }
            raw = new String(b, StandardCharsets.UTF_8);
            int esc = countEscapes(raw);
            escLabel.setText(esc > 0 ? esc + " Unicode escapes decoded" : " ");
            dirty = true;
            if (panel.isShowing()) {
                refresh();
            }
        }

        @Override
        public boolean isEnabledFor(HttpRequestResponse requestResponse) {
            if (nested) {
                return false;
            }
            boolean on;
            if (request) {
                on = true; // request tab is always available
            } else {
                on = requestResponse.hasResponse()
                        && needsDecode(requestResponse.response().toByteArray().getBytes());
            }
            log("isEnabledFor(" + (request ? "request" : "response") + ") = " + on);
            return on;
        }

        // ---- rendering ----

        private void zoomBy(int step) {
            if (linkZoom) {
                zoom(step);
                return;
            }
            int cur = request ? reqDelta : respDelta;
            int next = step == 0 ? 0 : Math.max(MIN_DELTA, Math.min(MAX_DELTA, cur + step));
            if (next == cur) {
                return;
            }
            if (request) {
                reqDelta = next;
            } else {
                respDelta = next;
            }
            api.persistence().preferences().setInteger(request ? PREF_FONT_REQ : PREF_FONT_RESP, next);
            refreshEditors();
        }

        private void setLinkZoom(boolean on) {
            linkZoom = on;
            Preferences prefs = api.persistence().preferences();
            prefs.setBoolean(PREF_LINK, on);
            if (!on) {
                // start independent from the current size so nothing jumps
                reqDelta = fontDelta;
                respDelta = fontDelta;
                prefs.setInteger(PREF_FONT_REQ, reqDelta);
                prefs.setInteger(PREF_FONT_RESP, respDelta);
            }
            refreshEditors();
        }

        private SimpleAttributeSet[] attrs() {
            int d = linkZoom ? fontDelta : (request ? reqDelta : respDelta);
            SimpleAttributeSet l = new SimpleAttributeSet();
            StyleConstants.setFontFamily(l, latinFamilyName);
            StyleConstants.setFontSize(l, BASE_LATIN + d);
            SimpleAttributeSet p = new SimpleAttributeSet();
            StyleConstants.setFontFamily(p, persianFamily);
            StyleConstants.setFontSize(p, BASE_PERSIAN + d);
            return new SimpleAttributeSet[]{l, p};
        }

        void onSettingsChanged() {
            linkBox.setSelected(linkZoom);
            pane.setComponentOrientation(orientation());
            if (panel.isShowing()) {
                refresh();
            } else {
                dirty = true;
            }
        }

        private void themeUi() {
            Pal pl = palette();
            Color tf = UIManager.getColor("TextField.foreground");
            if (tf != null) {
                searchFg = tf;
                search.setForeground(tf);
            }
            searchBar.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, lineColor()),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)));
            if (topBar != null) {
                topBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, lineColor()));
            }
            painter.fill = pl.hitFill;
            painter.border = pl.hitBorder;
            currentPainter.fill = pl.curFill;
            currentPainter.border = pl.curBorder;
            count.setForeground(pl.gutterText);
            escLabel.setForeground(pl.gutterText);
            gutter.update(pl);
        }

        private JComponent vsep() {
            JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
            sep.setPreferredSize(new Dimension(2, 18));
            return sep;
        }

        private int currentDelta() {
            return linkZoom ? fontDelta : (request ? reqDelta : respDelta);
        }

        // Line-number gutter, styled like Burp's Pretty view
        private final class Gutter extends JComponent {
            private Color text = Color.GRAY;
            private Color cur = Color.WHITE;
            private Color bg = Color.DARK_GRAY;
            private Color band = new Color(0, 0, 0, 0);
            private Color line = Color.GRAY;

            Gutter() {
                setOpaque(true);
                pane.addCaretListener(e -> repaint());
            }

            void update(Pal p) {
                text = p.gutterText;
                cur = p.gutterCur;
                bg = p.bg;
                band = p.bandOverlay;
                line = lineColor();
                setFont(new Font(latinFamilyName, Font.PLAIN, Math.max(8, BASE_LATIN + currentDelta() - 1)));
                revalidate();
                repaint();
            }

            @Override
            public Dimension getPreferredSize() {
                int lines = pane.getDocument().getDefaultRootElement().getElementCount();
                FontMetrics fm = getFontMetrics(getFont());
                int digits = Math.max(2, String.valueOf(lines).length());
                return new Dimension(fm.charWidth('0') * digits + 22, Integer.MAX_VALUE - 1000000);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Rectangle clip = g.getClipBounds();
                g.setColor(bg);
                g.fillRect(clip.x, clip.y, clip.width, clip.height);
                g.setFont(getFont());
                FontMetrics fm = g.getFontMetrics();
                Element root = pane.getDocument().getDefaultRootElement();
                int caret = pane.getCaretPosition();
                int caretLine = root.getElementIndex(caret);
                try {
                    java.awt.geom.Rectangle2D cr = pane.modelToView2D(caret);
                    if (cr != null && pane.getDocument().getLength() > 0) {
                        g.setColor(band);
                        g.fillRect(0, (int) cr.getY(), getWidth(), (int) cr.getHeight());
                    }
                    int first = root.getElementIndex(pane.viewToModel2D(new Point(0, clip.y)));
                    int last = root.getElementIndex(pane.viewToModel2D(new Point(0, clip.y + clip.height)));
                    for (int i = first; i <= last; i++) {
                        java.awt.geom.Rectangle2D r = pane.modelToView2D(root.getElement(i).getStartOffset());
                        if (r == null) {
                            continue;
                        }
                        String n = String.valueOf(i + 1);
                        g.setColor(i == caretLine ? cur : text);
                        int y = (int) r.getY() + ((int) r.getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                        g.drawString(n, getWidth() - 10 - fm.stringWidth(n), y);
                    }
                } catch (BadLocationException ignored) {
                }
                g.setColor(line);
                g.drawLine(getWidth() - 1, clip.y, getWidth() - 1, clip.y + clip.height);
            }
        }

        private void refresh() {
            dirty = false;
            safe(() -> {
                themeUi();
                if (nativeBox.isSelected()) {
                    loadNative();
                    return;
                }
                pane.setComponentOrientation(orientation());
                SimpleAttributeSet[] a = attrs();
                plain = renderTo(pane, raw, a[0], a[1]);
                pane.setCaretPosition(0);
                runSearch();
            });
        }

        private void toggleNative() {
            boolean on = nativeBox.isSelected();
            if (on) {
                ensureNative();
                loadNative();
            }
            cards.show(center, on ? "native" : "text");
            searchBar.setVisible(!on);
            if (!on) {
                refresh();
            }
        }

        private void ensureNative() {
            buildingNative = true;
            try {
                if (request && nativeReq == null) {
                    nativeReq = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY, EditorOptions.WRAP_LINES);
                    center.add(nativeReq.uiComponent(), "native");
                } else if (!request && nativeResp == null) {
                    nativeResp = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY, EditorOptions.WRAP_LINES);
                    center.add(nativeResp.uiComponent(), "native");
                }
            } finally {
                buildingNative = false;
            }
        }

        private void loadNative() {
            String src = raw.length() > MAX_CHARS ? raw.substring(0, MAX_CHARS) : raw;
            String text = transform(src).replace("\n", "\r\n");
            ByteArray ba = ByteArray.byteArray(text.getBytes(StandardCharsets.UTF_8));
            if (request) {
                nativeReq.setRequest(HttpRequest.httpRequest(ba));
            } else {
                nativeResp.setResponse(HttpResponse.httpResponse(ba));
            }
        }

        // ---- search with highlight ----

        void syncSearchOptions() {
            caseItem.setSelected(searchCase);
            regexItem.setSelected(searchRegex);
            autoItem.setSelected(searchAuto);
            runSearch();
        }

        private void runSearch() {
            Highlighter h = pane.getHighlighter();
            h.removeAllHighlights();
            currentTag = null;
            hits.clear();
            hitIndex = -1;
            String q = search.getText();
            clearBtn.setVisible(!q.isEmpty());
            search.setForeground(searchFg);
            if (box != null) {
                box.setError(false);
            }
            if (!q.isEmpty()) {
                try {
                    int flags = searchCase ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                    Matcher m = Pattern.compile(searchRegex ? q : Pattern.quote(q), flags).matcher(plain);
                    while (hits.size() < MAX_HITS && m.find()) {
                        if (m.end() > m.start()) {
                            hits.add(new int[]{m.start(), m.end()});
                        }
                    }
                } catch (PatternSyntaxException ex) {
                    search.setForeground(new Color(0xE06C6C));
                    if (box != null) {
                        box.setError(true);
                    }
                    count.setText("invalid regex");
                    return;
                }
                for (int[] r : hits) {
                    try {
                        h.addHighlight(r[0], r[1], painter);
                    } catch (BadLocationException ignored) {
                    }
                }
            }
            if (hits.isEmpty()) {
                count.setText("0 matches");
            } else {
                step(1, searchAuto);
            }
        }

        private void step(int d) {
            step(d, true);
        }

        private void step(int d, boolean scroll) {
            if (hits.isEmpty()) {
                return;
            }
            Highlighter h = pane.getHighlighter();
            if (currentTag != null) {
                h.removeHighlight(currentTag);
                currentTag = null;
            }
            hitIndex = (hitIndex + d + hits.size()) % hits.size();
            int[] r = hits.get(hitIndex);
            try {
                currentTag = h.addHighlight(r[0], r[1], currentPainter);
            } catch (BadLocationException ignored) {
            }
            if (scroll) {
                pane.setCaretPosition(r[0]);
                try {
                    java.awt.geom.Rectangle2D rect = pane.modelToView2D(r[0]);
                    if (rect != null) {
                        Rectangle box = rect.getBounds();
                        int vh = pane.getVisibleRect().height;
                        box.y -= vh / 3;
                        box.height += vh * 2 / 3;
                        pane.scrollRectToVisible(box);
                    }
                } catch (BadLocationException ignored) {
                }
            }
            count.setText((hitIndex + 1) + " / " + hits.size() + " matches");
        }

        // ---- the rest of the Montoya contract (read-only view) ----

        @Override
        public HttpRequest getRequest() {
            return current.request();
        }

        @Override
        public HttpResponse getResponse() {
            return current.response();
        }

        @Override
        public String caption() {
            return "Unicode/Persian";
        }

        @Override
        public Component uiComponent() {
            return panel;
        }

        @Override
        public Selection selectedData() {
            if (nativeBox.isSelected()) {
                Optional<Selection> s = request
                        ? (nativeReq == null ? Optional.empty() : nativeReq.selection())
                        : (nativeResp == null ? Optional.empty() : nativeResp.selection());
                return s.orElse(null);
            }
            String s = pane.getSelectedText();
            return (s == null || s.isEmpty()) ? null : Selection.selection(ByteArray.byteArray(s));
        }

        @Override
        public boolean isModified() {
            return false;
        }
    }

    // ---------- rendering ----------

    private static String pick(Set<String> available, String... candidates) {
        for (String c : candidates) {
            if (available.contains(c)) {
                return c;
            }
        }
        return Font.DIALOG;
    }

    private void render() {
        lastPlain = renderTo(output, input.getText());
        output.setCaretPosition(0);
    }

    private String renderTo(JTextPane pane, String raw) {
        return renderTo(pane, raw, latinAttr, persianAttr);
    }

    private String renderTo(JTextPane pane, String raw, AttributeSet latin, AttributeSet persian) {
        boolean cut = raw.length() > MAX_CHARS;
        if (cut) {
            raw = raw.substring(0, MAX_CHARS);
        }
        String plain = transform(raw);
        if (cut) {
            plain += "\n\n[input truncated]";
        }
        applyTheme(pane);
        StyledDocument doc = pane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            doc.insertString(0, plain, latin);
        } catch (BadLocationException ignored) {
        }
        applyPersianRuns(doc, plain, persian);
        colorize(pane, doc, plain);
        pane.setCaretPosition(0);
        return plain;
    }

    private void applyPersianRuns(StyledDocument doc, String s, AttributeSet persianAttr) {
        boolean cur = false;
        int runStart = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            int k = classify(s.charAt(i));
            boolean p = k == 1 || (k == -1 && cur);
            if (p != cur) {
                if (cur) {
                    doc.setCharacterAttributes(runStart, i - runStart, persianAttr, false);
                }
                cur = p;
                runStart = i;
            }
        }
        if (cur) {
            doc.setCharacterAttributes(runStart, n - runStart, persianAttr, false);
        }
    }

    // 1 = Persian/Arabic script, 0 = latin or structural, -1 = neutral (inherits previous)
    private static int classify(char c) {
        if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)
                || c == 0x200C || c == 0x200D) {
            return 1;
        }
        if (c == '"' || c == '{' || c == '}' || c == '[' || c == ']' || c == '\n') {
            return 0;
        }
        if (Character.isLetterOrDigit(c)) {
            return 0;
        }
        return -1;
    }

    private String transform(String raw) {
        String head = "";
        String body = raw;
        int nl = raw.indexOf('\n');
        String first = nl < 0 ? raw : raw.substring(0, nl);
        if (first.contains("HTTP/")) {
            int skip = 4;
            int p = raw.indexOf("\r\n\r\n");
            int q = raw.indexOf("\n\n");
            if (p < 0 || (q >= 0 && q < p)) {
                p = q;
                skip = 2;
            }
            if (p < 0) {
                head = raw;
                body = "";
            } else {
                head = raw.substring(0, p);
                body = raw.substring(p + skip);
            }
        }

        String t = body.strip();
        if (prettyBox.isSelected() && (t.startsWith("{") || t.startsWith("["))) {
            body = prettyJson(t);
        }
        body = unescape(body);
        if (digitsBox.isSelected()) {
            body = latinDigits(body);
        }
        String result = head.isEmpty() ? body : head + "\n\n" + body;
        return result.replace("\r", "");
    }

    private static String prettyJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + s.length() / 4);
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (inStr) {
                sb.append(c);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inStr = true;
                    sb.append(c);
                    break;
                case '{':
                case '[': {
                    sb.append(c);
                    int j = i + 1;
                    while (j < n && Character.isWhitespace(s.charAt(j))) {
                        j++;
                    }
                    if (j < n && (s.charAt(j) == '}' || s.charAt(j) == ']')) {
                        sb.append(s.charAt(j));
                        i = j;
                    } else {
                        depth++;
                        newline(sb, depth);
                    }
                    break;
                }
                case '}':
                case ']':
                    depth = Math.max(0, depth - 1);
                    newline(sb, depth);
                    sb.append(c);
                    break;
                case ',':
                    sb.append(c);
                    newline(sb, depth);
                    break;
                case ':':
                    sb.append(": ");
                    break;
                default:
                    if (!Character.isWhitespace(c)) {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static void newline(StringBuilder sb, int depth) {
        sb.append('\n');
        for (int i = 0; i < depth * 2; i++) {
            sb.append(' ');
        }
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        int n = s.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= n) {
                sb.append(c);
                continue;
            }
            char d = s.charAt(i + 1);
            if (d == 'u' && i + 5 < n) {
                int v = hex4(s, i + 2);
                if (v >= 0x20) {
                    sb.append((char) v);
                    i += 5;
                } else {
                    sb.append(c);
                }
            } else if (d == 'n') {
                sb.append('\n');
                i++;
            } else if (d == 't') {
                sb.append('\t');
                i++;
            } else if (d == 'r') {
                i++;
            } else if (d == '/') {
                sb.append('/');
                i++;
            } else {
                sb.append(c).append(d);
                i++;
            }
        }
        return sb.toString();
    }

    private static int hex4(String s, int at) {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int h = Character.digit(s.charAt(at + i), 16);
            if (h < 0) {
                return -1;
            }
            v = (v << 4) | h;
        }
        return v;
    }

    private static String latinDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0660 && c <= 0x0669) {
                sb.append((char) ('0' + (c - 0x0660)));
            } else if (c >= 0x06F0 && c <= 0x06F9) {
                sb.append((char) ('0' + (c - 0x06F0)));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}