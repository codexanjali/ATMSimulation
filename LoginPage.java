import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Login screen for the Banking ATM Simulation.
 * MySQL is the source of truth for account and transaction data.
 */
public class LoginPage extends JFrame {

    // ---------- Theme ----------
    static final Color DEEP_PURPLE = new Color(0x50207A);
    static final Color LAVENDER = new Color(0xD6B9FC);
    static final Color PERIWINKLE = new Color(0x838CE5);
    static final Color BACKGROUND = new Color(0xF5F3FF);
    static final Color ERROR_RED = new Color(0xC62828);
    static final Color SUCCESS_GREEN = new Color(0x2E7D32);
    static final Color MUTED_TEXT = new Color(0x6B6580);
    static final int FIELD_WIDTH = 320;

    // ---------- Banking rules ----------
    static final BigDecimal DAILY_WITHDRAWAL_LIMIT =
            new BigDecimal("10000.00");

    private static volatile String lastRegistrationError;

    public enum WithdrawalStatus {
        OK,
        INVALID_AMOUNT,
        INSUFFICIENT_BALANCE,
        DAILY_LIMIT_EXCEEDED,
        INSUFFICIENT_ATM_CASH,
        DATABASE_ERROR
    }

    // ---------- Transaction ----------
    public static final class Transaction {

        private final String type;
        private final BigDecimal amount;
        private final LocalDateTime timestamp;
        private final BigDecimal balanceAfter;

        Transaction(
                String type,
                BigDecimal amount,
                LocalDateTime timestamp,
                BigDecimal balanceAfter
        ) {
            this.type = type;
            this.amount = amount;
            this.timestamp = timestamp;
            this.balanceAfter = balanceAfter;
        }

        public String getType() {
            return type;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public BigDecimal getBalanceAfter() {
            return balanceAfter;
        }
    }

    // ---------- Account ----------
    public static final class Account {

        private final long accountId;
        private final String fullName;
        private final String cardNumber;

        private BigDecimal balance;
        private Transaction lastTransaction;
        private String lastError;

        Account(
                long accountId,
                String fullName,
                String cardNumber,
                BigDecimal balance
        ) {
            this.accountId = accountId;
            this.fullName = fullName;
            this.cardNumber = cardNumber;
            this.balance = balance;
        }

        public String getFullName() {
            return fullName;
        }

        public String getCardNumber() {
            return cardNumber;
        }

        long getAccountId() {
            return accountId;
        }

        public String getMaskedCardNumber() {
            return "**** **** "
                    + cardNumber.substring(cardNumber.length() - 4);
        }

        public synchronized String getLastError() {
            return lastError;
        }
        
        private void fail(SQLException e) {
                    lastError = "Database error. Please try again.";
                    System.err.println("Database error: " + e);
                }
        public synchronized BigDecimal balanceEnquiry() {
            return getBalance();
        }

        public BigDecimal getDailyLimit() {
            return DAILY_WITHDRAWAL_LIMIT;
        }

        

        public synchronized BigDecimal getBalance() {
            try {
                balance = Database.getBalance(accountId);
            } catch (SQLException e) {
                fail(e);
            }

            return balance;
        }

        public synchronized boolean pinMatches(String candidate) {
            try {
                return Database.verifyPin(accountId, candidate);
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "Database unavailable", e
                );
            }
        }

        public synchronized boolean changePin(
                String currentPin,
                String newPin
        ) {
            lastError = null;

            try {
                return Database.changePin(
                        accountId, currentPin, newPin
                );
            } catch (SQLException e) {
                fail(e);
                return false;
            }
        }

        public synchronized boolean deposit(BigDecimal amount) {
            lastError = null;

            try {
                Transaction tx = Database.deposit(accountId, amount);

                if (tx == null) {
                    return false;
                }

                lastTransaction = tx;
                balance = tx.getBalanceAfter();

                return true;

            } catch (SQLException e) {
                fail(e);
                return false;
            }
        }

        public synchronized WithdrawalStatus checkWithdrawal(
                BigDecimal amount
        ) {
            try {
                return Database.checkWithdrawal(accountId, amount);
            } catch (SQLException e) {
                fail(e);
                return WithdrawalStatus.DATABASE_ERROR;
            }
        }

        public synchronized boolean withdraw(BigDecimal amount) {
            lastError = null;

            try {
                Database.WithdrawResult result =
                        Database.withdraw(accountId, amount);

                if (result.status != WithdrawalStatus.OK) {
                    lastError = "Withdrawal not allowed: "
                            + describe(result.status) + ".";
                    return false;
                }

                lastTransaction = result.transaction;
                balance = result.transaction.getBalanceAfter();

                return true;

            } catch (SQLException e) {
                fail(e);
                return false;
            }
        }

        private static String describe(WithdrawalStatus status) {
            switch (status) {
                case INSUFFICIENT_BALANCE:
                    return "insufficient balance";

                case DAILY_LIMIT_EXCEEDED:
                    return "daily limit exceeded";

                case INSUFFICIENT_ATM_CASH:
                    return "this ATM does not have enough cash";

                default:
                    return "invalid amount";
            }
        }

        public synchronized BigDecimal getWithdrawnToday() {
            try {
                return Database.getWithdrawnToday(accountId);
            } catch (SQLException e) {
                fail(e);
                return new BigDecimal("0.00");
            }
        }

        public synchronized BigDecimal getRemainingDailyAllowance() {
            BigDecimal remaining =
                    DAILY_WITHDRAWAL_LIMIT.subtract(getWithdrawnToday());

            return remaining.signum() < 0
                    ? new BigDecimal("0.00")
                    : remaining;
        }

        public synchronized List<Transaction> getTransactions() {
            lastError = null;

            try {
                return Database.getTransactions(accountId);
            } catch (SQLException e) {
                fail(e);
                return new ArrayList<>();
            }
        }

        public synchronized Transaction getLastTransaction() {
            if (lastTransaction != null) {
                return lastTransaction;
            }

            try {
                return Database.getLastTransaction(accountId);
            } catch (SQLException e) {
                fail(e);
                return null;
            }
        }
    }

    // ---------- Account store ----------

    public static boolean cardExists(String cardNumber) {
        try {
            return Database.cardExists(cardNumber);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Database unavailable", e
            );
        }
    }

    public static Account findAccount(String cardNumber) {
        try {
            return Database.findAccountByCard(cardNumber);
        } catch (SQLException e) {
            System.err.println("Database error: " + e);

            throw new IllegalStateException(
                    "Database unavailable", e
            );
        }
    }

    public static boolean addAccount(
            String fullName,
            String cardNumber,
            String pin
    ) {
        lastRegistrationError = null;

        try {
            if (Database.cardExists(cardNumber)) {
                lastRegistrationError =
                        "This card number is already registered. "
                                + "Please use a different one.";
                return false;
            }

            Database.createAccount(fullName, cardNumber, pin);
            return true;

        } catch (SQLException e) {
            String message = e.getMessage() == null
                    ? ""
                    : e.getMessage().toLowerCase(Locale.ROOT);

            if (e.getErrorCode() == 1062
                    || message.contains("duplicate")) {

                lastRegistrationError =
                        "This card number is already registered. "
                                + "Please use a different one.";

            } else {
                System.err.println("Registration failed: " + e);

                lastRegistrationError =
                        "Could not save the account to MySQL. "
                                + "Check the database connection and tables.";
            }

            return false;
        }
    }

    public static String getLastRegistrationError() {
        return lastRegistrationError;
    }

    static String saveWarning() {
        return "";
    }

    // ---------- Login screen components ----------

    private final JTextField cardField = new JTextField();

    private final JPasswordField pinField =
            new JPasswordField();

    private final JLabel messageLabel =
            createMessageLabel();

    private final JButton loginButton =
            createButton("Login", true);

    public LoginPage() {
        this(null, null);
    }

    public LoginPage(String prefillCard, String notice) {
        super("Banking ATM - Login");

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel body = createCardBody();

        addHeader(
                body,
                "Banking ATM",
                "Welcome back! Please log in with your card number and PIN."
        );

        styleInput(cardField);
        styleInput(pinField);

        addLabeledField(body, "Card Number", cardField);
        addLabeledField(body, "ATM PIN", pinField);

        body.add(messageLabel);
        body.add(Box.createVerticalStrut(8));

        JButton createAccountButton =
                createButton("Create Account", false);

        body.add(loginButton);
        body.add(Box.createVerticalStrut(10));
        body.add(createAccountButton);
        body.add(Box.createVerticalStrut(18));

        body.add(createNoteLabel(
                "Note: new accounts are saved in MySQL with "
                        + "a ₹500 opening balance."
        ));

        loginButton.addActionListener(e -> handleLogin());

        createAccountButton.addActionListener(
                e -> openRegisterPage()
        );

        getRootPane().setDefaultButton(loginButton);

        if (prefillCard != null) {
            cardField.setText(prefillCard);
        }

        if (notice != null) {
            showMessage(messageLabel, notice, false);
        }

        applyLockState();

        setContentPane(
                createPageBackground(
                        wrapInCard(body)
                )
        );

        pack();
        setResizable(false);
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(() ->
                (prefillCard != null
                        ? pinField
                        : cardField).requestFocusInWindow()
        );
    }

    // ---------- Login logic with audit logging ----------

    private void handleLogin() {

        if (SessionSecurity.isLocked()) {
            applyLockState();
            return;
        }

        String card = cardField.getText().trim();
        String pin = new String(pinField.getPassword());

        // Clear the PIN from the visible input field.
        pinField.setText("");

        // Record an attempt when either field is empty.
        if (card.isEmpty() || pin.isEmpty()) {

            recordLoginAttemptSafely(
                    card,
                    null,
                    false,
                    "EMPTY_FIELDS"
            );

            showMessage(
                    messageLabel,
                    "Please enter both your card number and PIN.",
                    true
            );

            return;
        }

        Account account;

        // First look up the account in MySQL.
        try {
            account = findAccount(card);

        } catch (IllegalStateException e) {
            showMessage(
                    messageLabel,
                    "Database unavailable. Please try again.",
                    true
            );
            return;
        }

        // Verify the PIN and update the existing session lock counter.
        SessionSecurity.Result result =
                SessionSecurity.verify(account, pin);

        // Do not mislabel a database outage as an incorrect PIN.
        if (result == SessionSecurity.Result.ERROR) {
            showMessage(
                    messageLabel,
                    "Database unavailable. Please try again.",
                    true
            );
            return;
        }

        boolean success =
                result == SessionSecurity.Result.SUCCESS;

        Long accountId = account == null
                ? null
                : account.getAccountId();

        // Record successful and failed authentication attempts.
        recordLoginAttemptSafely(
                card,
                accountId,
                success,
                success ? null : "INVALID_CARD_OR_PIN"
        );

        if (success) {
            navigate(this, new HomePage(account));
            return;
        }

        showMessage(
                messageLabel,
                SessionSecurity.failureMessage(
                        result,
                        "card number or PIN"
                ),
                true
        );

        applyLockState();
        pinField.requestFocusInWindow();
    }

    /**
     * Saves login attempts in the physical MySQL login table.
     * The PIN is deliberately never stored.
     */
    private void recordLoginAttemptSafely(
            String cardNumber,
            Long accountId,
            boolean success,
            String reason
    ) {
        try {
            Database.recordLoginAttempt(
                    cardNumber,
                    accountId,
                    success,
                    reason
            );

        } catch (SQLException e) {
            System.err.println(
                    "Could not record login attempt in MySQL: "
                            + e.getMessage()
            );
        }
    }

    private void applyLockState() {

        if (SessionSecurity.isLocked()) {

            cardField.setEnabled(false);
            pinField.setEnabled(false);
            loginButton.setEnabled(false);

            showMessage(
                    messageLabel,
                    SessionSecurity.LOCKED_MESSAGE,
                    true
            );
        }
    }

    private void openRegisterPage() {
        new RegisterPage().setVisible(true);
        dispose();
    }

    // ---------- Shared UI helpers ----------

    static JPanel createCardBody() {
        JPanel body = new JPanel();

        body.setLayout(
                new BoxLayout(body, BoxLayout.Y_AXIS)
        );

        body.setBackground(Color.WHITE);

        body.setBorder(
                new EmptyBorder(28, 36, 28, 36)
        );

        return body;
    }

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

    static JPanel createPageBackground(JPanel card) {

        JPanel page = new JPanel(new GridBagLayout());

        page.setBackground(BACKGROUND);
        page.setBorder(
                new EmptyBorder(32, 40, 32, 40)
        );

        page.add(card);

        return page;
    }

    static void addHeader(
            JPanel body,
            String title,
            String subtitle
    ) {
        JLabel titleLabel = new JLabel(title);

        titleLabel.setFont(
                new Font("SansSerif", Font.BOLD, 28)
        );

        titleLabel.setForeground(DEEP_PURPLE);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitleLabel = new JLabel(
                htmlWrap(subtitle, FIELD_WIDTH - 10)
        );

        subtitleLabel.setFont(
                new Font("SansSerif", Font.PLAIN, 14)
        );

        subtitleLabel.setForeground(MUTED_TEXT);
        subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        body.add(titleLabel);
        body.add(Box.createVerticalStrut(6));
        body.add(subtitleLabel);
        body.add(Box.createVerticalStrut(22));
    }

    static void addLabeledField(
            JPanel body,
            String labelText,
            JTextField field
    ) {
        JLabel label = new JLabel(labelText);

        label.setFont(
                new Font("SansSerif", Font.BOLD, 13)
        );

        label.setForeground(DEEP_PURPLE);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        label.setBorder(
                new EmptyBorder(0, 0, 6, 0)
        );

        body.add(label);
        body.add(field);
        body.add(Box.createVerticalStrut(14));
    }

    static void styleInput(JTextField field) {

        field.setFont(
                new Font("SansSerif", Font.PLAIN, 15)
        );

        field.setForeground(Color.DARK_GRAY);
        field.setCaretColor(DEEP_PURPLE);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);

        field.setPreferredSize(
                new Dimension(FIELD_WIDTH, 42)
        );

        field.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, 42)
        );

        field.setBorder(
                BorderFactory.createCompoundBorder(
                        new LineBorder(PERIWINKLE, 1),
                        new EmptyBorder(0, 12, 0, 12)
                )
        );
    }

    static JButton createButton(
            String text,
            boolean primary
    ) {
        final Color base =
                primary ? DEEP_PURPLE : Color.WHITE;

        final Color hover =
                primary
                        ? new Color(0x6A2FA0)
                        : new Color(0xEFE6FD);

        final Color pressed =
                primary
                        ? new Color(0x3E1860)
                        : LAVENDER;

        JButton button = new JButton(text) {

            @Override
            protected void paintComponent(Graphics g) {

                Graphics2D g2 =
                        (Graphics2D) g.create();

                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );

                if (getModel().isPressed()) {
                    g2.setColor(pressed);

                } else if (getModel().isRollover()) {
                    g2.setColor(hover);

                } else {
                    g2.setColor(base);
                }

                g2.fillRoundRect(
                        0,
                        0,
                        getWidth(),
                        getHeight(),
                        10,
                        10
                );

                if (!primary) {
                    g2.setColor(PERIWINKLE);

                    g2.drawRoundRect(
                            0,
                            0,
                            getWidth() - 1,
                            getHeight() - 1,
                            10,
                            10
                    );
                }

                g2.dispose();

                super.paintComponent(g);
            }
        };

        button.setFont(
                new Font("SansSerif", Font.BOLD, 15)
        );

        button.setForeground(
                primary ? Color.WHITE : DEEP_PURPLE
        );

        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setRolloverEnabled(true);

        button.setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.HAND_CURSOR
                )
        );

        button.setAlignmentX(Component.LEFT_ALIGNMENT);

        button.setPreferredSize(
                new Dimension(FIELD_WIDTH, 44)
        );

        button.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, 44)
        );

        return button;
    }

    static JLabel createMessageLabel() {

        JLabel label = new JLabel(" ");

        label.setFont(
                new Font("SansSerif", Font.PLAIN, 13)
        );

        label.setVerticalAlignment(SwingConstants.TOP);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        label.setPreferredSize(
                new Dimension(FIELD_WIDTH, 40)
        );

        label.setMinimumSize(
                new Dimension(FIELD_WIDTH, 40)
        );

        label.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, 40)
        );

        return label;
    }

    static JLabel createNoteLabel(String text) {

        JLabel label = new JLabel(
                htmlWrap(text, FIELD_WIDTH - 10)
        );

        label.setFont(
                new Font("SansSerif", Font.PLAIN, 12)
        );

        label.setForeground(MUTED_TEXT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        return label;
    }

    static void showMessage(
            JLabel label,
            String text,
            boolean isError
    ) {
        label.setForeground(
                isError ? ERROR_RED : SUCCESS_GREEN
        );

        label.setText(
                htmlWrap(text, FIELD_WIDTH - 10)
        );
    }

    private static String htmlWrap(
            String text,
            int width
    ) {
        String safe = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        return "<html><div style='width:"
                + width
                + "px'>"
                + safe
                + "</div></html>";
    }

    static void navigate(JFrame from, JFrame to) {
        to.setVisible(true);
        from.dispose();
    }

    static String formatMoney(BigDecimal amount) {
        return "\u20B9"
                + String.format(Locale.US, "%,.2f", amount);
    }

    static BigDecimal parseAmount(String text) {

        String trimmed = text.trim();

        if (!trimmed.matches("\\d+(\\.\\d{1,2})?")) {
            return null;
        }

        BigDecimal value =
                new BigDecimal(trimmed).setScale(2);

        return value.signum() > 0
                ? value
                : null;
    }

    static JLabel createValueLabel(String text) {

        JLabel label = new JLabel(text);

        label.setFont(
                new Font("SansSerif", Font.BOLD, 22)
        );

        label.setForeground(DEEP_PURPLE);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        return label;
    }

    static JPanel createInfoPanel(
            String caption,
            JLabel valueLabel
    ) {
        JLabel captionLabel = new JLabel(caption);

        captionLabel.setFont(
                new Font("SansSerif", Font.PLAIN, 12)
        );

        captionLabel.setForeground(MUTED_TEXT);
        captionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel panel = new JPanel();

        panel.setLayout(
                new BoxLayout(panel, BoxLayout.Y_AXIS)
        );

        panel.setBackground(BACKGROUND);

        panel.setBorder(
                BorderFactory.createCompoundBorder(
                        new LineBorder(LAVENDER, 1),
                        new EmptyBorder(12, 16, 12, 16)
                )
        );

        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(captionLabel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(valueLabel);

        panel.setMaximumSize(
                new Dimension(
                        Integer.MAX_VALUE,
                        panel.getPreferredSize().height
                )
        );

        return panel;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(
                () -> new LoginPage().setVisible(true)
        );
    }
}