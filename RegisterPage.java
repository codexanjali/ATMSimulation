import javax.swing.*;

/**
 * Registration screen for the Banking ATM Simulation.
 * Saves new accounts into the shared store owned by LoginPage (which also writes atm_data.txt).
 */
public class RegisterPage extends JFrame {

    private final JTextField nameField = new JTextField();
    private final JTextField cardField = new JTextField();
    private final JPasswordField pinField = new JPasswordField();
    private final JPasswordField confirmPinField = new JPasswordField();
    private final JLabel messageLabel = LoginPage.createMessageLabel();

    public RegisterPage() {
        super("Banking ATM - Register");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel body = LoginPage.createCardBody();
        LoginPage.addHeader(body, "Banking ATM", "Create your account to start using the ATM.");

        LoginPage.styleInput(nameField);
        LoginPage.styleInput(cardField);
        LoginPage.styleInput(pinField);
        LoginPage.styleInput(confirmPinField);

        LoginPage.addLabeledField(body, "Full Name", nameField);
        LoginPage.addLabeledField(body, "Card Number (12 digits)", cardField);
        LoginPage.addLabeledField(body, "Create PIN (4 digits)", pinField);
        LoginPage.addLabeledField(body, "Confirm PIN", confirmPinField);

        body.add(messageLabel);
        body.add(Box.createVerticalStrut(8));

        JButton registerButton = LoginPage.createButton("Register", true);
        JButton backButton = LoginPage.createButton("Back to Login", false);
        body.add(registerButton);
        body.add(Box.createVerticalStrut(10));
        body.add(backButton);
        body.add(Box.createVerticalStrut(18));
        body.add(LoginPage.createNoteLabel(
                "Note: accounts are saved in atm_data.txt in the folder where the application is run. "
                + "Your PIN is stored only as a salted hash."));

        registerButton.addActionListener(e -> handleRegister());
        backButton.addActionListener(e -> openLoginPage(null, null));
        getRootPane().setDefaultButton(registerButton);

        setContentPane(LoginPage.createPageBackground(LoginPage.wrapInCard(body)));
        pack();
        setResizable(false);
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(nameField::requestFocusInWindow);
    }

    private void handleRegister() {
        String name = nameField.getText().trim();
        String card = cardField.getText().trim();
        String pin = new String(pinField.getPassword());
        String confirmPin = new String(confirmPinField.getPassword());

        if (name.isEmpty() || card.isEmpty() || pin.isEmpty() || confirmPin.isEmpty()) {
            showError("All fields are required. Please fill in every field.");
            return;
        }
        if (!card.matches("\\d{12}")) {
            showError("Card number must contain exactly 12 digits (numbers only).");
            return;
        }
        if (!pin.matches("\\d{4}")) {
            showError("PIN must contain exactly 4 digits (numbers only).");
            return;
        }
        if (!pin.equals(confirmPin)) {
            showError("PIN and Confirm PIN do not match.");
            return;
        }
        if (!LoginPage.addAccount(name, card, pin)) {
            showError("This card number is already registered. Please use a different one.");
            return;
        }

        // Success: return to the login screen with the card number pre-filled.
        openLoginPage(card, "Account created successfully! Please log in with your new PIN."
                + LoginPage.saveWarning());
    }

    private void showError(String text) {
        LoginPage.showMessage(messageLabel, text, true);
    }

    private void openLoginPage(String prefillCard, String notice) {
        new LoginPage(prefillCard, notice).setVisible(true);
        dispose();
    }
}