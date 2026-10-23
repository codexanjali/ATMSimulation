import javax.swing.*;
import java.math.BigDecimal;

/** Deposits money into the logged-in account after PIN verification. */
public class CashDeposit extends JFrame {

    // Composition: this screen holds a reference to the account passed in by HomePage.
    private final LoginPage.Account account;
    private final JPasswordField pinField = new JPasswordField();
    private final JTextField amountField = new JTextField();
    private final JLabel balanceLabel = LoginPage.createValueLabel("");
    private final JLabel messageLabel = LoginPage.createMessageLabel();
    private final JButton depositButton = LoginPage.createButton("Deposit", true);

    public CashDeposit(LoginPage.Account account) {
        super("Banking ATM - Cash Deposit");
        this.account = account;
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel body = LoginPage.createCardBody();
        LoginPage.addHeader(body, "Cash Deposit", "Enter your PIN and the amount you want to deposit.");

        refreshBalance();
        body.add(LoginPage.createInfoPanel("Available Balance", balanceLabel));
        body.add(Box.createVerticalStrut(16));

        LoginPage.styleInput(pinField);
        LoginPage.styleInput(amountField);
        LoginPage.addLabeledField(body, "Enter ATM PIN", pinField);
        LoginPage.addLabeledField(body, "Amount (\u20B9)", amountField);
        body.add(messageLabel);
        body.add(Box.createVerticalStrut(8));

        JButton backButton = LoginPage.createButton("Back to Dashboard", false);
        depositButton.addActionListener(e -> handleDeposit());
        backButton.addActionListener(e -> LoginPage.navigate(this, new HomePage(account)));
        body.add(depositButton);
        body.add(Box.createVerticalStrut(10));
        body.add(backButton);

        getRootPane().setDefaultButton(depositButton);
        applyLockState();   // opening this screen cannot bypass a lockout
        setContentPane(LoginPage.createPageBackground(LoginPage.wrapInCard(body)));
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
    }

    /** Order: locked? -> PIN empty? -> PIN correct? -> amount valid? -> deposit -> update screen -> receipt. */
    private void handleDeposit() {
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
        if (amount == null || !account.deposit(amount)) {
            LoginPage.showMessage(messageLabel,
                    "Enter a valid amount greater than zero (numbers only, up to 2 decimals).", true);
            return;
        }
        LoginPage.Transaction transaction = account.getLastTransaction();
        amountField.setText("");
        refreshBalance();
        LoginPage.showMessage(messageLabel, "Deposit of " + LoginPage.formatMoney(amount)
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
                    SessionSecurity.failureMessage(result, "PIN") + " Deposit cancelled.", true);
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
            depositButton.setEnabled(false);
            LoginPage.showMessage(messageLabel, SessionSecurity.LOCKED_MESSAGE, true);
        }
    }

    private void refreshBalance() {
        balanceLabel.setText(LoginPage.formatMoney(account.getBalance()));
    }
}