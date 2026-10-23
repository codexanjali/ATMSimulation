import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.print.PrinterException;
import java.time.format.DateTimeFormatter;

/**
 * Receipt shown after a successful deposit or withdrawal.
 * It only displays data taken from the stored Transaction and the Account.
 */
public final class ReceiptDialog {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");

    private ReceiptDialog() { }

    /** Shows a modal receipt for a transaction that has already succeeded. */
    static void show(JFrame owner, LoginPage.Account account, LoginPage.Transaction tx) {
        if (tx == null) {
            return;
        }
        JDialog dialog = new JDialog(owner, "Transaction Receipt", true);

        JPanel body = LoginPage.createCardBody();
        LoginPage.addHeader(body, "Banking ATM Simulation", "Transaction Receipt");
        body.add(createRow("Transaction Type", tx.getType(), LoginPage.DEEP_PURPLE));
        body.add(createRow("Card Number", account.getMaskedCardNumber(), LoginPage.DEEP_PURPLE));
        body.add(createRow("Amount", LoginPage.formatMoney(tx.getAmount()), LoginPage.DEEP_PURPLE));
        body.add(createRow("Date & Time", tx.getTimestamp().format(TIME_FORMAT), LoginPage.DEEP_PURPLE));
        body.add(createRow("Updated Balance", LoginPage.formatMoney(tx.getBalanceAfter()), LoginPage.DEEP_PURPLE));
        body.add(createRow("Status", "Successful", LoginPage.SUCCESS_GREEN));
        body.add(Box.createVerticalStrut(20));

        JButton printButton = LoginPage.createButton("Print Receipt", false);
        JButton closeButton = LoginPage.createButton("Close", true);
        printButton.addActionListener(e -> print(dialog, buildText(account, tx)));
        closeButton.addActionListener(e -> dialog.dispose());
        body.add(printButton);
        body.add(Box.createVerticalStrut(10));
        body.add(closeButton);

        dialog.getRootPane().setDefaultButton(closeButton);
        dialog.setContentPane(LoginPage.createPageBackground(LoginPage.wrapInCard(body)));
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);   // modal: returns when the dialog is closed
    }

    private static JPanel createRow(String caption, String value, Color valueColor) {
        JLabel captionLabel = new JLabel(caption);
        captionLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
        captionLabel.setForeground(LoginPage.MUTED_TEXT);

        JLabel valueLabel = new JLabel(value, SwingConstants.RIGHT);
        valueLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        valueLabel.setForeground(valueColor);

        JPanel row = new JPanel(new BorderLayout(20, 0));
        row.setBackground(Color.WHITE);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, LoginPage.LAVENDER), new EmptyBorder(9, 0, 9, 0)));
        row.add(captionLabel, BorderLayout.WEST);
        row.add(valueLabel, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** Plain-text version of the receipt, used for printing. */
    private static String buildText(LoginPage.Account account, LoginPage.Transaction tx) {
        return String.format(
                "BANKING ATM SIMULATION%nTransaction Receipt%n"
                + "------------------------------------%n"
                + "Type      : %s%nCard      : %s%nAmount    : %s%nDate/Time : %s%nBalance   : %s%nStatus    : Successful%n",
                tx.getType(), account.getMaskedCardNumber(), LoginPage.formatMoney(tx.getAmount()),
                tx.getTimestamp().format(TIME_FORMAT), LoginPage.formatMoney(tx.getBalanceAfter()));
    }

    /** Standard Java printing: JTextArea.print() opens the system print dialog. */
    private static void print(JDialog parent, String text) {
        JTextArea area = new JTextArea(text);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        try {
            area.print();
        } catch (PrinterException ex) {
            JOptionPane.showMessageDialog(parent, "Could not print the receipt: " + ex.getMessage(),
                    "Print", JOptionPane.WARNING_MESSAGE);
        }
    }
}