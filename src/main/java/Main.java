import javax.swing.*;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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

    private final List<Entity> entities = new ArrayList<>();
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

        windowTitle = loadTitle(locale);

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

    private String loadTitle(Locale locale) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle("i18n.messages", locale);
            return bundle.getString("app.title");
        } catch (MissingResourceException e) {
            System.err.println("Bundle 'i18n.messages' not found; using hard-coded title.");
            return "Demo001";
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
        entities.add(new ParticleSystem(windowWidth, windowHeight));
    }

    private void createAndShowWindow() {
        JFrame frame = new JFrame(windowTitle);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(windowWidth, windowHeight);
        frame.setLocationRelativeTo(null);

        GamePanel panel = new GamePanel(entities);
        frame.setContentPane(panel);
        frame.setVisible(true);
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

    private static class GamePanel extends JPanel {
        private final List<Entity> entities;

        GamePanel(List<Entity> entities) {
            this.entities = entities;
            setBackground(Color.BLACK);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            for (Entity e : entities) e.draw(g2);
        }
    }

    public static void main(String[] args) {
        Main app = new Main();
        app.run(args);
    }
}
