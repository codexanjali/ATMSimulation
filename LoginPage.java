import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Login screen for the Banking ATM Simulation.
 *
 * This class also owns the shared user store (loaded from / saved to atm_data.txt)
 * and the common theme/UI helpers, so the other screens can reuse them without extra files.
 */
public class LoginPage extends JFrame {

    // ---------- Theme ----------
    static final Color DEEP_PURPLE = new Color(0x50207A);
    static final Color LAVENDER    = new Color(0xD6B9FC);
    static final Color PERIWINKLE  = new Color(0x838CE5);
    static final Color BACKGROUND  = new Color(0xF5F3FF);
    static final Color ERROR_RED   = new Color(0xC62828);
    static final Color SUCCESS_GREEN = new Color(0x2E7D32);
    static final Color MUTED_TEXT  = new Color(0x6B6580);
    static final int   FIELD_WIDTH = 320;

    // ---------- Banking rules ----------
    static final BigDecimal DAILY_WITHDRAWAL_LIMIT = new BigDecimal("20000.00");

    // ---------- Shared user store (single instance for the whole app) ----------
    private static final Map<String, Account> ACCOUNTS = new HashMap<>();

    // ---------- Persistence ----------
    /** Data file: atm_data.txt in the folder the application is started from. Delete it to reset the demo. */
    static final Path DATA_FILE = Paths.get(System.getProperty("user.dir"), "atm_data.txt");
    private static final Object FILE_LOCK = new Object();
    private static boolean saveDisabled = false;   // true if an unreadable file must not be overwritten
    private static boolean lastSaveOk = true;

    static {
        loadAccounts();
    }

    /** Result of checking a withdrawal against the balance and the daily limit. */
    public enum WithdrawalStatus { OK, INVALID_AMOUNT, INSUFFICIENT_BALANCE, DAILY_LIMIT_EXCEEDED }

    /** One successful deposit or withdrawal. Immutable. */
    public static final class Transaction {
        private final String type;
        private final BigDecimal amount;
        private final LocalDateTime timestamp;
        private final BigDecimal balanceAfter;

        /** Used for a new transaction: timestamp is "now". */
        Transaction(String type, BigDecimal amount, BigDecimal balanceAfter) {
            this(type, amount, LocalDateTime.now(), balanceAfter);
        }

        /** Used when loading a saved transaction. */
        Transaction(String type, BigDecimal amount, LocalDateTime timestamp, BigDecimal balanceAfter) {
            this.type = type;
            this.amount = amount;
            this.timestamp = timestamp;
            this.balanceAfter = balanceAfter;
        }

        public String getType()              { return type; }
        public BigDecimal getAmount()        { return amount; }
        public LocalDateTime getTimestamp()  { return timestamp; }
        public BigDecimal getBalanceAfter()  { return balanceAfter; }
    }

    /** A registered ATM account: owns its own PIN credentials, balance and transaction history. */
    public static final class Account {
        private static final int PBKDF2_ITERATIONS = 65536;
        private static final SecureRandom RANDOM = new SecureRandom();

        private final String fullName;
        private final String cardNumber;
        private byte[] pinSalt;     // random per account
        private byte[] pinHash;     // PBKDF2 hash of the PIN (the PIN itself is never stored)
        private BigDecimal balance = new BigDecimal("0.00");
        private final List<Transaction> transactions = new ArrayList<>();

        /** New account: creates a fresh random salt and stores only the hash of the PIN. */
        Account(String fullName, String cardNumber, String pin) {
            this.fullName = fullName;
            this.cardNumber = cardNumber;
            this.pinSalt = newSalt();
            this.pinHash = hashPin(pin, pinSalt);
        }

        /** Account restored from the data file. */
        private Account(String fullName, String cardNumber, byte[] pinSalt, byte[] pinHash, BigDecimal balance) {
            this.fullName = fullName;
            this.cardNumber = cardNumber;
            this.pinSalt = pinSalt;
            this.pinHash = pinHash;
            this.balance = balance;
        }

        public String getFullName()   { return fullName; }
        public String getCardNumber() { return cardNumber; }

        /** Card number with all but the last four digits hidden. */
        public String getMaskedCardNumber() {
            return "**** **** " + cardNumber.substring(cardNumber.length() - 4);
        }

        public synchronized BigDecimal getBalance() { return balance; }

        public synchronized boolean pinMatches(String candidate) {
            if (candidate == null) {
                return false;
            }
            return MessageDigest.isEqual(pinHash, hashPin(candidate, pinSalt));
        }

        /** Changes the PIN only if the current PIN is correct, then saves. */
        public boolean changePin(String currentPin, String newPin) {
            synchronized (this) {
                if (!pinMatches(currentPin)) {
                    return false;
                }
                pinSalt = newSalt();
                pinHash = hashPin(newPin, pinSalt);
            }
            saveAccounts();
            return true;
        }

        /** Adds money, records the transaction and saves. Returns false (no change) for invalid amounts. */
        public boolean deposit(BigDecimal amount) {
            synchronized (this) {
                if (amount == null || amount.signum() <= 0) {
                    return false;
                }
                balance = balance.add(amount);
                transactions.add(new Transaction("Deposit", amount, balance));
            }
            saveAccounts();
            return true;
        }

        /** Checks a withdrawal without changing anything: amount, then balance, then daily limit. */
        public synchronized WithdrawalStatus checkWithdrawal(BigDecimal amount) {
            if (amount == null || amount.signum() <= 0) {
                return WithdrawalStatus.INVALID_AMOUNT;
            }
            if (amount.compareTo(balance) > 0) {
                return WithdrawalStatus.INSUFFICIENT_BALANCE;
            }
            if (amount.compareTo(getRemainingDailyAllowance()) > 0) {
                return WithdrawalStatus.DAILY_LIMIT_EXCEEDED;
            }
            return WithdrawalStatus.OK;
        }

        /** Removes money, records the transaction and saves. Returns false (no change) if any check fails. */
        public boolean withdraw(BigDecimal amount) {
            synchronized (this) {
                if (checkWithdrawal(amount) != WithdrawalStatus.OK) {
                    return false;
                }
                balance = balance.subtract(amount);
                transactions.add(new Transaction("Withdrawal", amount, balance));
            }
            saveAccounts();
            return true;
        }

        /** Total of today's successful withdrawals (local date), calculated from the saved history. */
        public synchronized BigDecimal getWithdrawnToday() {
            LocalDate today = LocalDate.now();
            BigDecimal total = new BigDecimal("0.00");
            for (Transaction t : transactions) {
                if (t.getType().equals("Withdrawal") && t.getTimestamp().toLocalDate().equals(today)) {
                    total = total.add(t.getAmount());
                }
            }
            return total;
        }

        /** Daily limit minus today's withdrawals (never negative). Independent of the account balance. */
        public synchronized BigDecimal getRemainingDailyAllowance() {
            BigDecimal remaining = DAILY_WITHDRAWAL_LIMIT.subtract(getWithdrawnToday());
            return remaining.signum() < 0 ? new BigDecimal("0.00") : remaining;
        }

        /** Snapshot copy of the history, oldest first. */
        public synchronized List<Transaction> getTransactions() {
            return new ArrayList<>(transactions);
        }

        /** The most recent transaction, or null if there is none. */
        public synchronized Transaction getLastTransaction() {
            return transactions.isEmpty() ? null : transactions.get(transactions.size() - 1);
        }

        /** Appends this account's lines to the data file text. */
        private synchronized void appendRecords(StringBuilder out) {
            out.append("ACCOUNT|").append(cardNumber)
               .append('|').append(URLEncoder.encode(fullName, StandardCharsets.UTF_8))
               .append('|').append(Base64.getEncoder().encodeToString(pinSalt))
               .append('|').append(Base64.getEncoder().encodeToString(pinHash))
               .append('|').append(balance.toPlainString()).append('\n');
            for (Transaction t : transactions) {
                out.append("TXN|").append(cardNumber)
                   .append('|').append(t.getType())
                   .append('|').append(t.getAmount().toPlainString())
                   .append('|').append(t.getTimestamp())
                   .append('|').append(t.getBalanceAfter().toPlainString()).append('\n');
            }
        }

        private static byte[] newSalt() {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            return salt;
        }

        /** PBKDF2 (HMAC-SHA256) from the standard library. */
        private static byte[] hashPin(String pin, byte[] salt) {
            try {
                PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("PBKDF2 is not available", e);
            }
        }
    }

    // =====================================================================
    // Account store
    // =====================================================================

    /** Returns true if a card number is already registered. */
    public static synchronized boolean cardExists(String cardNumber) {
        return ACCOUNTS.containsKey(cardNumber);
    }

    /** Returns the account for a card number, or null if there is none. Does not check the PIN. */
    public static synchronized Account findAccount(String cardNumber) {
        return ACCOUNTS.get(cardNumber);
    }

    /** Adds a new account and saves. Returns false (and stores nothing) if the card already exists. */
    public static boolean addAccount(String fullName, String cardNumber, String pin) {
        synchronized (LoginPage.class) {
            if (ACCOUNTS.containsKey(cardNumber)) {
                return false;
            }
            ACCOUNTS.put(cardNumber, new Account(fullName, cardNumber, pin));
        }
        saveAccounts();
        return true;
    }

    // =====================================================================
    // File persistence (plain text, one record per line, no external libraries)
    //   ACCOUNT|card|name(URL-encoded)|salt(Base64)|pinHash(Base64)|balance
    //   TXN|card|type|amount|timestamp(ISO)|balanceAfter
    // =====================================================================

    private static void loadAccounts() {
        if (!Files.exists(DATA_FILE)) {
            return;   // first run: nothing to load
        }
        Map<String, Account> loaded = new HashMap<>();
        try {
            for (String line : Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] p = line.split("\\|", -1);
                if (p.length == 6 && p[0].equals("ACCOUNT")) {
                    BigDecimal balance = new BigDecimal(p[5]).setScale(2);
                    if (!p[1].matches("\\d{12}") || loaded.containsKey(p[1]) || balance.signum() < 0) {
                        throw new IllegalArgumentException("Invalid account record");
                    }
                    loaded.put(p[1], new Account(
                            URLDecoder.decode(p[2], StandardCharsets.UTF_8), p[1],
                            Base64.getDecoder().decode(p[3]), Base64.getDecoder().decode(p[4]), balance));
                } else if (p.length == 6 && p[0].equals("TXN")) {
                    Account owner = loaded.get(p[1]);
                    BigDecimal amount = new BigDecimal(p[3]).setScale(2);
                    if (owner == null || amount.signum() <= 0
                            || !(p[2].equals("Deposit") || p[2].equals("Withdrawal"))) {
                        throw new IllegalArgumentException("Invalid transaction record");
                    }
                    owner.transactions.add(new Transaction(p[2], amount,
                            LocalDateTime.parse(p[4]), new BigDecimal(p[5]).setScale(2)));
                } else {
                    throw new IllegalArgumentException("Unrecognised line");
                }
            }
            ACCOUNTS.putAll(loaded);   // only used if the whole file was valid
        } catch (IOException e) {
            // Could not read the file: do not guess, and do not overwrite it later.
            saveDisabled = true;
            lastSaveOk = false;
            System.err.println("Could not read " + DATA_FILE + ": " + e.getMessage());
        } catch (RuntimeException e) {
            // Corrupted content: keep the bad file as a backup and start with an empty store.
            Path backup = DATA_FILE.resolveSibling("atm_data.corrupt-" + System.currentTimeMillis() + ".txt");
            try {
                Files.move(DATA_FILE, backup, StandardCopyOption.REPLACE_EXISTING);
                System.err.println("Data file was corrupted; moved to " + backup);
            } catch (IOException moveError) {
                saveDisabled = true;     // cannot protect the old file, so never overwrite it
                lastSaveOk = false;
            }
        }
    }

    /** Writes every account to the data file (temp file first, then replace). Returns true on success. */
    static boolean saveAccounts() {
        List<Account> snapshot;
        synchronized (LoginPage.class) {
            snapshot = new ArrayList<>(ACCOUNTS.values());
        }
        StringBuilder text = new StringBuilder();
        for (Account account : snapshot) {
            account.appendRecords(text);
        }
        synchronized (FILE_LOCK) {
            if (saveDisabled || snapshot.isEmpty()) {
                lastSaveOk = !saveDisabled;
                return lastSaveOk;       // never replace saved data with an empty store
            }
            try {
                Path temp = DATA_FILE.resolveSibling("atm_data.txt.tmp");
                Files.write(temp, text.toString().getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temp, DATA_FILE, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, DATA_FILE, StandardCopyOption.REPLACE_EXISTING);
                }
                lastSaveOk = true;
            } catch (IOException e) {
                lastSaveOk = false;
                System.err.println("Could not save " + DATA_FILE + ": " + e.getMessage());
            }
            return lastSaveOk;
        }
    }

    /** Empty text normally; a short warning to append to a message if the last save failed. */
    static String saveWarning() {
        return lastSaveOk ? "" : " (Warning: data could not be saved to disk.)";
    }

    // ---------- Components ----------
    private final JTextField cardField = new JTextField();
    private final JPasswordField pinField = new JPasswordField();
    private final JLabel messageLabel = createMessageLabel();
    private final JButton loginButton = createButton("Login", true);

    public LoginPage() {
        this(null, null);
    }

    /**
     * @param prefillCard card number to pre-fill (may be null)
     * @param notice      success notice to show on open (may be null)
     */
    public LoginPage(String prefillCard, String notice) {
        super("Banking ATM - Login");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel body = createCardBody();
        addHeader(body, "Banking ATM", "Welcome back! Please log in with your card number and PIN.");

        styleInput(cardField);
        styleInput(pinField);
        addLabeledField(body, "Card Number", cardField);
        addLabeledField(body, "ATM PIN", pinField);

        body.add(messageLabel);
        body.add(Box.createVerticalStrut(8));

        JButton createAccountButton = createButton("Create Account", false);
        body.add(loginButton);
        body.add(Box.createVerticalStrut(10));
        body.add(createAccountButton);
        body.add(Box.createVerticalStrut(18));
        body.add(createNoteLabel("Note: accounts are saved in atm_data.txt in the folder where the application is run."));

        loginButton.addActionListener(e -> handleLogin());
        createAccountButton.addActionListener(e -> openRegisterPage());
        getRootPane().setDefaultButton(loginButton);

        if (prefillCard != null) {
            cardField.setText(prefillCard);
        }
        if (notice != null) {
            showMessage(messageLabel, notice, false);
        }
        applyLockState();   // a locked session stays locked after logout / re-opening this screen

        setContentPane(createPageBackground(wrapInCard(body)));
        pack();
        setResizable(false);
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(() ->
                (prefillCard != null ? pinField : cardField).requestFocusInWindow());
    }

    private void handleLogin() {
        if (SessionSecurity.isLocked()) {
            applyLockState();
            return;
        }
        String card = cardField.getText().trim();
        String pin = new String(pinField.getPassword());
        pinField.setText("");

        if (card.isEmpty() || pin.isEmpty()) {
            showMessage(messageLabel, "Please enter both your card number and PIN.", true);
            return;
        }

        SessionSecurity.Result result = SessionSecurity.verify(findAccount(card), pin);
        if (result == SessionSecurity.Result.SUCCESS) {
            navigate(this, new HomePage(findAccount(card)));
            return;
        }
        showMessage(messageLabel, SessionSecurity.failureMessage(result, "card number or PIN"), true);
        applyLockState();
        pinField.requestFocusInWindow();
    }

    /** Disables the login form while the session is locked. */
    private void applyLockState() {
        if (SessionSecurity.isLocked()) {
            cardField.setEnabled(false);
            pinField.setEnabled(false);
            loginButton.setEnabled(false);
            showMessage(messageLabel, SessionSecurity.LOCKED_MESSAGE, true);
        }
    }

    private void openRegisterPage() {
        new RegisterPage().setVisible(true);
        dispose();
    }

    // =====================================================================
    // Shared UI helpers (package-private, reused by the other screens)
    // =====================================================================

    static JPanel createCardBody() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Color.WHITE);
        body.setBorder(new EmptyBorder(28, 36, 28, 36));
        return body;
    }

    /** Wraps a form body in a white card with a deep purple accent bar on top. */
    static JPanel wrapInCard(JPanel body) {
        JPanel accent = new JPanel();
        accent.setBackground(DEEP_PURPLE);
        accent.setPreferredSize(new Dimension(10, 8));

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(new LineBorder(LAVENDER, 1));
        card.add(accent, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    /** Centers the card on the light page background. */
    static JPanel createPageBackground(JPanel card) {
        JPanel page = new JPanel(new GridBagLayout());
        page.setBackground(BACKGROUND);
        page.setBorder(new EmptyBorder(32, 40, 32, 40));
        page.add(card);
        return page;
    }

    static void addHeader(JPanel body, String title, String subtitle) {
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 28));
        titleLabel.setForeground(DEEP_PURPLE);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitleLabel = new JLabel(htmlWrap(subtitle, FIELD_WIDTH - 10));
        subtitleLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        subtitleLabel.setForeground(MUTED_TEXT);
        subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        body.add(titleLabel);
        body.add(Box.createVerticalStrut(6));
        body.add(subtitleLabel);
        body.add(Box.createVerticalStrut(22));
    }

    static void addLabeledField(JPanel body, String labelText, JTextField field) {
        JLabel label = new JLabel(labelText);
        label.setFont(new Font("SansSerif", Font.BOLD, 13));
        label.setForeground(DEEP_PURPLE);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(0, 0, 6, 0));
        body.add(label);
        body.add(field);
        body.add(Box.createVerticalStrut(14));
    }

    static void styleInput(JTextField field) {
        field.setFont(new Font("SansSerif", Font.PLAIN, 15));
        field.setForeground(Color.DARK_GRAY);
        field.setCaretColor(DEEP_PURPLE);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setPreferredSize(new Dimension(FIELD_WIDTH, 42));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        field.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(PERIWINKLE, 1), new EmptyBorder(0, 12, 0, 12)));
    }

    /** Creates a flat rounded button. Primary = filled purple, secondary = white with outline. */
    static JButton createButton(String text, boolean primary) {
        final Color base = primary ? DEEP_PURPLE : Color.WHITE;
        final Color hover = primary ? new Color(0x6A2FA0) : new Color(0xEFE6FD);
        final Color pressed = primary ? new Color(0x3E1860) : LAVENDER;

        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isPressed()) {
                    g2.setColor(pressed);
                } else if (getModel().isRollover()) {
                    g2.setColor(hover);
                } else {
                    g2.setColor(base);
                }
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                if (!primary) {
                    g2.setColor(PERIWINKLE);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setFont(new Font("SansSerif", Font.BOLD, 15));
        button.setForeground(primary ? Color.WHITE : DEEP_PURPLE);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setRolloverEnabled(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setPreferredSize(new Dimension(FIELD_WIDTH, 44));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        return button;
    }

    /** Fixed-height label so the layout does not jump when a message appears. */
    static JLabel createMessageLabel() {
        JLabel label = new JLabel(" ");
        label.setFont(new Font("SansSerif", Font.PLAIN, 13));
        label.setVerticalAlignment(SwingConstants.TOP);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setPreferredSize(new Dimension(FIELD_WIDTH, 40));
        label.setMinimumSize(new Dimension(FIELD_WIDTH, 40));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return label;
    }

    static JLabel createNoteLabel(String text) {
        JLabel label = new JLabel(htmlWrap(text, FIELD_WIDTH - 10));
        label.setFont(new Font("SansSerif", Font.PLAIN, 12));
        label.setForeground(MUTED_TEXT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    static void showMessage(JLabel label, String text, boolean isError) {
        label.setForeground(isError ? ERROR_RED : SUCCESS_GREEN);
        label.setText(htmlWrap(text, FIELD_WIDTH - 10));
    }

    private static String htmlWrap(String text, int width) {
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='width:" + width + "px'>" + safe + "</div></html>";
    }

    /** Opens the target window, then disposes the current one (never two windows at once). */
    static void navigate(JFrame from, JFrame to) {
        to.setVisible(true);
        from.dispose();
    }

    /** Formats money as e.g. "\u20B91,250.50". */
    static String formatMoney(BigDecimal amount) {
        return "\u20B9" + String.format(Locale.US, "%,.2f", amount);
    }

    /** Parses a positive amount with at most 2 decimals. Returns null if invalid. */
    static BigDecimal parseAmount(String text) {
        String trimmed = text.trim();
        if (!trimmed.matches("\\d+(\\.\\d{1,2})?")) {
            return null;
        }
        BigDecimal value = new BigDecimal(trimmed).setScale(2);
        return value.signum() > 0 ? value : null;
    }

    static JLabel createValueLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("SansSerif", Font.BOLD, 22));
        label.setForeground(DEEP_PURPLE);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** Light panel showing a small caption above a large value (balance, card number...). */
    static JPanel createInfoPanel(String caption, JLabel valueLabel) {
        JLabel captionLabel = new JLabel(caption);
        captionLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        captionLabel.setForeground(MUTED_TEXT);
        captionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BACKGROUND);
        panel.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(LAVENDER, 1), new EmptyBorder(12, 16, 12, 16)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(captionLabel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(valueLabel);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LoginPage().setVisible(true));
    }
}