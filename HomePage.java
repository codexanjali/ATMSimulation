import javax.swing.*;
import java.util.function.Supplier;

/** Dashboard shown after a successful login. */
public class HomePage extends JFrame {

    public HomePage(LoginPage.Account account) {
        super("Banking ATM - Home");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel body = LoginPage.createCardBody();
        LoginPage.addHeader(body, "Banking ATM", "Welcome, " + account.getFullName() + "!");

        body.add(LoginPage.createInfoPanel("Card Number",
                LoginPage.createValueLabel(account.getMaskedCardNumber())));
        body.add(Box.createVerticalStrut(10));
        body.add(LoginPage.createInfoPanel("Current Balance",
                LoginPage.createValueLabel(LoginPage.formatMoney(account.getBalance()))));
        body.add(Box.createVerticalStrut(20));

        addNavButton(body, "Balance Enquiry", true, () -> new BalanceEnquiry(account));
        addNavButton(body, "Cash Withdrawal", true, () -> new CashWithdrawal(account));
        addNavButton(body, "Cash Deposit", true, () -> new CashDeposit(account));
        addNavButton(body, "Change PIN", true, () -> new ChangePIN(account));
        addNavButton(body, "Transaction History", true, () -> new TransactionHistory(account));
        body.add(Box.createVerticalStrut(8));
        addNavButton(body, "Logout", false, LoginPage::new);

        setContentPane(LoginPage.createPageBackground(LoginPage.wrapInCard(body)));
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
    }

    private void addNavButton(JPanel body, String text, boolean primary, Supplier<JFrame> target) {
        JButton button = LoginPage.createButton(text, primary);
        button.addActionListener(e -> LoginPage.navigate(this, target.get()));
        body.add(button);
        body.add(Box.createVerticalStrut(10));
    }
}