import javax.swing.*;

/** Changes the PIN of the logged-in account. The current-PIN check uses the session-wide lockout. */
public class ChangePIN extends JFrame {

    private final LoginPage.Account account;
    private final JPasswordField currentPinField = new JPasswordField();
    private final JPasswordField newPinField = new JPasswordField();
    private final JPasswordField confirmPinField = new JPasswordField();
    private final JLabel messageLabel = LoginPage.createMessageLabel();
    private final JButton changeButton = LoginPage.createButton("Change PIN", true);

    public ChangePIN(LoginPage.Account account) {
        super("Banking ATM - Change PIN");
        this.account = account;
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel body = LoginPage.createCardBody();
        LoginPage.addHeader(body, "Change PIN", "Enter your current PIN, then choose a new 4-digit PIN.");

        LoginPage.styleInput(currentPinField);
        LoginPage.styleInput(newPinField);
        LoginPage.styleInput(confirmPinField);
        LoginPage.addLabeledField(body, "Current PIN", currentPinField);
        LoginPage.addLabeledField(body, "New PIN (4 digits)", newPinField);
        LoginPage.addLabeledField(body, "Confirm New PIN", confirmPinField);
        body.add(messageLabel);
        body.add(Box.createVerticalStrut(8));

        JButton backButton = LoginPage.createButton("Back to Dashboard", false);
        changeButton.addActionListener(e -> handleChangePin());
        backButton.addActionListener(e -> LoginPage.navigate(this, new HomePage(account)));
        body.add(changeButton);
        body.add(Box.createVerticalStrut(10));
        body.add(backButton);

        getRootPane().setDefaultButton(changeButton);
        applyLockState();
        setContentPane(LoginPage.createPageBackground(LoginPage.wrapInCard(body)));
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
    }

    private void handleChangePin() {
        if (SessionSecurity.isLocked()) {
            applyLockState();
            return;
        }
        String current = new String(currentPinField.getPassword());
        String newPin = new String(newPinField.getPassword());
        String confirm = new String(confirmPinField.getPassword());
        currentPinField.setText("");

        if (current.isEmpty() || newPin.isEmpty() || confirm.isEmpty()) {
            error("All fields are required.");
            return;
        }
        SessionSecurity.Result result = SessionSecurity.verify(account, current);
        if (result != SessionSecurity.Result.SUCCESS) {
            error(SessionSecurity.failureMessage(result, "current PIN"));
            applyLockState();
        } else if (!newPin.matches("\\d{4}")) {
            error("New PIN must contain exactly 4 digits (numbers only).");
        } else if (!newPin.equals(confirm)) {
            error("New PIN and Confirm PIN do not match.");
        } else if (newPin.equals(current)) {
            error("New PIN must be different from your current PIN.");
        } else if (!account.changePin(current, newPin)) {
            error("PIN could not be changed. Please try again.");
        } else {
            newPinField.setText("");
            confirmPinField.setText("");
            LoginPage.showMessage(messageLabel, "PIN changed successfully. Use your new PIN next time you log in."
                    + LoginPage.saveWarning(), false);
        }
    }

    /** Disables the form while the session is locked. */
    private void applyLockState() {
        if (SessionSecurity.isLocked()) {
            currentPinField.setEnabled(false);
            newPinField.setEnabled(false);
            confirmPinField.setEnabled(false);
            changeButton.setEnabled(false);
            LoginPage.showMessage(messageLabel, SessionSecurity.LOCKED_MESSAGE, true);
        }
    }

    private void error(String text) {
        LoginPage.showMessage(messageLabel, text, true);
    }
}