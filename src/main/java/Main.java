import javax.swing.*;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class Main {

    private static final int    DEFAULT_WIDTH  = 800;
    private static final int    DEFAULT_HEIGHT = 600;
    private static final String DEFAULT_LANG   = "EN";

    private final int    windowWidth;
    private final int    windowHeight;
    private final String windowTitle;
    private final ResourceBundle bundle;

    private final List<Entity>  entities   = new ArrayList<>();
    private final InputState    inputState = new InputState();
    private long lastTime = System.nanoTime();

    public Main() {
        System.out.println("Starting Application...");

        Properties config = loadConfig();

        windowWidth  = Integer.parseInt(
            config.getProperty("app.window.width",  String.valueOf(DEFAULT_WIDTH)));
        windowHeight = Integer.parseInt(
            config.getProperty("app.window.height", String.valueOf(DEFAULT_HEIGHT)));

        String langCode = config.getProperty("app.language.default", DEFAULT_LANG);
        Locale locale   = Locale.of(langCode.toLowerCase(Locale.ROOT));

        bundle      = loadBundle(locale);
        windowTitle = getMessage("app.title", "Demo001");

        System.out.printf("Window: %dx%d  locale: %s  title: \"%s\"%n",
            windowWidth, windowHeight, locale, windowTitle);
    }

    private Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream in = Main.class.getResourceAsStream("/config.properties")) {
            if (in != null) {
                props.load(in);
            } else {
                System.out.println("config.properties not found on classpath; using defaults.");
            }
        } catch (IOException e) {
            System.err.println("Failed to load config.properties: " + e.getMessage());
        }
        return props;
    }

    private ResourceBundle loadBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle("i18n.messages", locale);
        } catch (MissingResourceException e) {
            System.err.println("Bundle 'i18n.messages' not found; using hard-coded text.");
            return null;
        }
    }

    private String getMessage(String key, String fallback) {
        if (bundle == null) return fallback;
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return fallback;
        }
    }

    public void run(String[] args) {
        for (String arg : args) {
            System.out.printf("-> arg:%s%n", arg);
        }
        initEntities();
        SwingUtilities.invokeLater(this::createAndShowWindow);
    }

    private void initEntities() {
        entities.add(new ParticleSystem(windowWidth, windowHeight, inputState));
    }

    private void createAndShowWindow() {
        JFrame frame = new JFrame(windowTitle);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(windowWidth, windowHeight);
        frame.setLocationRelativeTo(null);

        String exitTitle   = getMessage("app.exit.confirm.title",   "Confirm Exit");
        String exitMessage = getMessage("app.exit.confirm.message", "Are you sure you want to quit?");

        GamePanel panel = new GamePanel(entities, inputState, exitTitle, exitMessage);
        frame.setContentPane(panel);
        frame.setVisible(true);
        panel.requestFocusInWindow();
        startGameLoop(panel);
    }

    private void startGameLoop(GamePanel panel) {
        javax.swing.Timer timer = new javax.swing.Timer(16, e -> {
            long now = System.nanoTime();
            double dt = (now - lastTime) / 1_000_000_000.0;
            lastTime = now;
            for (Entity entity : entities) entity.update(dt);
            panel.repaint();
        });
        timer.start();
    }

    private void dispose() {
        System.out.println("done.");
    }

    private static class GamePanel extends JPanel
            implements KeyListener, MouseListener, MouseMotionListener {

        private final List<Entity> entities;
        private final InputState   input;
        private final String       exitTitle;
        private final String       exitMessage;

        GamePanel(List<Entity> entities, InputState input, String exitTitle, String exitMessage) {
            this.entities    = entities;
            this.input       = input;
            this.exitTitle   = exitTitle;
            this.exitMessage = exitMessage;
            setBackground(Color.BLACK);
            setFocusable(true);
            addKeyListener(this);
            addMouseListener(this);
            addMouseMotionListener(this);
        }

        @Override
        public void addNotify() {
            super.addNotify();
            requestFocusInWindow();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            for (Entity e : entities) e.draw(g2);
        }

        // --- KeyListener ---

        @Override
        public void keyPressed(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_LEFT,  KeyEvent.VK_A -> input.yawLeft    = true;
                case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> input.yawRight   = true;
                case KeyEvent.VK_UP,    KeyEvent.VK_W -> input.pitchUp    = true;
                case KeyEvent.VK_DOWN,  KeyEvent.VK_S -> input.pitchDown  = true;
                case KeyEvent.VK_Q      -> input.rollLeft   = true;
                case KeyEvent.VK_E      -> input.rollRight  = true;
                case KeyEvent.VK_SPACE  -> input.brake      = true;
                case KeyEvent.VK_CONTROL -> input.thrustUp   = true;
                case KeyEvent.VK_SHIFT   -> input.thrustDown = true;
                case KeyEvent.VK_ESCAPE  -> confirmExit();
            }
        }

        @Override
        public void keyReleased(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_LEFT,  KeyEvent.VK_A -> input.yawLeft    = false;
                case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> input.yawRight   = false;
                case KeyEvent.VK_UP,    KeyEvent.VK_W -> input.pitchUp    = false;
                case KeyEvent.VK_DOWN,  KeyEvent.VK_S -> input.pitchDown  = false;
                case KeyEvent.VK_Q      -> input.rollLeft   = false;
                case KeyEvent.VK_E      -> input.rollRight  = false;
                case KeyEvent.VK_SPACE  -> input.brake      = false;
                case KeyEvent.VK_CONTROL -> input.thrustUp   = false;
                case KeyEvent.VK_SHIFT   -> input.thrustDown = false;
            }
        }

        @Override public void keyTyped(KeyEvent e) {}

        private void confirmExit() {
            int choice = JOptionPane.showConfirmDialog(
                this, exitMessage, exitTitle,
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                System.exit(0);
            }
        }

        // --- MouseListener ---

        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            if (SwingUtilities.isLeftMouseButton(e)) {
                input.mouseDragging = true;
                updateMouseNorm(e);
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                input.mouseDragging = false;
                input.mouseNormX    = 0;
                input.mouseNormY    = 0;
            }
        }

        @Override public void mouseClicked(MouseEvent e)  {}
        @Override public void mouseEntered(MouseEvent e)  {}
        @Override public void mouseExited(MouseEvent e)   {}

        // --- MouseMotionListener ---

        @Override
        public void mouseDragged(MouseEvent e) {
            if (input.mouseDragging) updateMouseNorm(e);
        }

        @Override public void mouseMoved(MouseEvent e) {}

        private void updateMouseNorm(MouseEvent e) {
            double halfW = getWidth()  / 2.0;
            double halfH = getHeight() / 2.0;
            input.mouseNormX = Math.clamp((e.getX() - halfW) / halfW, -1.0, 1.0);
            input.mouseNormY = Math.clamp((e.getY() - halfH) / halfH, -1.0, 1.0);
        }
    }

    public static void main(String[] args) {
        Main app = new Main();
        app.run(args);
    }
}
