import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Withdraws money from the logged-in account after PIN verification (daily limit applies). */
public class CashWithdrawal extends JFrame {

    private static final int[] QUICK_AMOUNTS = {500, 1000, 2000, 5000, 10000, 20000};

    // Composition: this screen holds a reference to the account passed in by HomePage.
    private final LoginPage.Account account;
    private final JPasswordField pinField = new JPasswordField();
    private final JTextField amountField = new JTextField();
    private final JLabel balanceLabel = LoginPage.createValueLabel("");
    private final JLabel allowanceLabel = new JLabel();
    private final JLabel messageLabel = LoginPage.createMessageLabel();
    private final JButton withdrawButton = LoginPage.createButton("Withdraw", true);
    private final List<JButton> quickButtons = new ArrayList<>();

    public CashWithdrawal(LoginPage.Account account) {
        super("Banking ATM - Cash Withdrawal");
        this.account = account;
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel body = LoginPage.createCardBody();
        LoginPage.addHeader(body, "Cash Withdrawal", "Enter your PIN, then choose a quick amount or enter your own.");

        allowanceLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        allowanceLabel.setForeground(LoginPage.MUTED_TEXT);
        allowanceLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        refreshAmounts();
        body.add(LoginPage.createInfoPanel("Available Balance", balanceLabel));
        body.add(Box.createVerticalStrut(8));
        body.add(allowanceLabel);
        body.add(Box.createVerticalStrut(14));

        LoginPage.styleInput(pinField);
        LoginPage.styleInput(amountField);
        LoginPage.addLabeledField(body, "Enter ATM PIN", pinField);
        LoginPage.addLabeledField(body, "Amount (\u20B9)", amountField);
        body.add(createQuickSelectPanel());
        body.add(Box.createVerticalStrut(12));
        body.add(messageLabel);
        body.add(Box.createVerticalStrut(8));

        JButton backButton = LoginPage.createButton("Back to Dashboard", false);
        withdrawButton.addActionListener(e -> handleWithdraw());
        backButton.addActionListener(e -> LoginPage.navigate(this, new HomePage(account)));
        body.add(withdrawButton);
        body.add(Box.createVerticalStrut(10));
        body.add(backButton);

        getRootPane().setDefaultButton(withdrawButton);
        applyLockState();   // opening this screen cannot bypass a lockout
        setContentPane(LoginPage.createPageBackground(LoginPage.wrapInCard(body)));
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
    }

    private JPanel createQuickSelectPanel() {
        JPanel grid = new JPanel(new GridLayout(2, 3, 8, 8));
        grid.setBackground(Color.WHITE);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));
        for (int value : QUICK_AMOUNTS) {
            JButton quick = LoginPage.createButton("\u20B9" + value, false);
            quick.setPreferredSize(new Dimension(90, 38));
            quick.addActionListener(e -> amountField.setText(String.valueOf(value)));
            quickButtons.add(quick);
            grid.add(quick);
        }
        return grid;
    }

    /** Order: locked? -> PIN empty? -> PIN correct? -> amount valid? -> balance/daily limit -> withdraw -> receipt. */
    private void handleWithdraw() {
        if (SessionSecurity.isLocked()) {
            applyLockState();
            return;
        }
        if (!verifyPin()) {
            return;
        }
        String text = amountField.getText();
        if (text.trim().isEmpty()) {
            LoginPage.showMessage(messageLabel, "Please enter an amount.", true);
            return;
        }
        BigDecimal amount = LoginPage.parseAmount(text);
        if (amount == null) {
            LoginPage.showMessage(messageLabel,
                    "Enter a valid amount greater than zero (numbers only, up to 2 decimals).", true);
            return;
        }

        // The account decides whether the withdrawal is allowed; nothing changes unless it is OK.
        switch (account.checkWithdrawal(amount)) {
            case INSUFFICIENT_BALANCE:
                LoginPage.showMessage(messageLabel, "Insufficient balance. Available: "
                        + LoginPage.formatMoney(account.getBalance()), true);
                return;
            case DAILY_LIMIT_EXCEEDED:
                LoginPage.showMessage(messageLabel, "Daily withdrawal limit exceeded. You can withdraw up to "
                        + LoginPage.formatMoney(account.getRemainingDailyAllowance()) + " more today.", true);
                return;
            case INVALID_AMOUNT:
                LoginPage.showMessage(messageLabel,
                        "Enter a valid amount greater than zero (numbers only, up to 2 decimals).", true);
                return;
            default:
                break;
        }
        if (!account.withdraw(amount)) {
            LoginPage.showMessage(messageLabel, "Withdrawal could not be completed. Please try again.", true);
            return;
        }

        LoginPage.Transaction transaction = account.getLastTransaction();
        amountField.setText("");
        refreshAmounts();
        LoginPage.showMessage(messageLabel, "Withdrawal of " + LoginPage.formatMoney(amount)
                + " successful. New balance: " + LoginPage.formatMoney(account.getBalance())
                + LoginPage.saveWarning(), false);
        ReceiptDialog.show(this, account, transaction);
    }

    /** Checks the entered PIN through the session-wide attempt counter. The PIN field is cleared on every attempt. */
    private boolean verifyPin() {
        String pin = new String(pinField.getPassword());
        pinField.setText("");
        if (pin.isEmpty()) {
            LoginPage.showMessage(messageLabel, "Please enter your ATM PIN.", true);
            return false;
        }
        SessionSecurity.Result result = SessionSecurity.verify(account, pin);
        if (result != SessionSecurity.Result.SUCCESS) {
            LoginPage.showMessage(messageLabel,
                    SessionSecurity.failureMessage(result, "PIN") + " Withdrawal cancelled.", true);
            applyLockState();
            return false;
        }
        return true;
    }

    /** Disables the form while the session is locked. */
    private void applyLockState() {
        if (SessionSecurity.isLocked()) {
            pinField.setEnabled(false);
            amountField.setEnabled(false);
            withdrawButton.setEnabled(false);
            for (JButton quick : quickButtons) {
                quick.setEnabled(false);
            }
            LoginPage.showMessage(messageLabel, SessionSecurity.LOCKED_MESSAGE, true);
        }
    }

    /** Updates the balance and the daily-limit summary. */
    private void refreshAmounts() {
        balanceLabel.setText(LoginPage.formatMoney(account.getBalance()));
        allowanceLabel.setText("<html><div style='width:310px'>Daily limit: "
                + LoginPage.formatMoney(LoginPage.DAILY_WITHDRAWAL_LIMIT)
                + "<br>Withdrawn today: " + LoginPage.formatMoney(account.getWithdrawnToday())
                + " &nbsp;|&nbsp; Remaining: " + LoginPage.formatMoney(account.getRemainingDailyAllowance())
                + "</div></html>");
    }
}