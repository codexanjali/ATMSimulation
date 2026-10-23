import javax.swing.*;

/** Shows the live balance of the logged-in account. */
public class BalanceEnquiry extends JFrame {

    public BalanceEnquiry(LoginPage.Account account) {
        super("Banking ATM - Balance Enquiry");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel body = LoginPage.createCardBody();
        LoginPage.addHeader(body, "Balance Enquiry", "Your current available balance.");

        body.add(LoginPage.createInfoPanel("Card Number",
                LoginPage.createValueLabel(account.getMaskedCardNumber())));
        body.add(Box.createVerticalStrut(10));
        body.add(LoginPage.createInfoPanel("Available Balance",
                LoginPage.createValueLabel(LoginPage.formatMoney(account.getBalance()))));
        body.add(Box.createVerticalStrut(20));

        JButton backButton = LoginPage.createButton("Back to Dashboard", true);
        backButton.addActionListener(e -> LoginPage.navigate(this, new HomePage(account)));
        body.add(backButton);

        getRootPane().setDefaultButton(backButton);
        setContentPane(LoginPage.createPageBackground(LoginPage.wrapInCard(body)));
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
    }
}