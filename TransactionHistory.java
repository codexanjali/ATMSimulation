import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Lists the logged-in account's successful deposits and withdrawals (newest first). */
public class TransactionHistory extends JFrame {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");

    public TransactionHistory(LoginPage.Account account) {
        super("Banking ATM - Transaction History");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel body = LoginPage.createCardBody();
        LoginPage.addHeader(body, "Transaction History", "Your deposits and withdrawals, newest first.");

        List<LoginPage.Transaction> transactions = account.getTransactions();
        if (transactions.isEmpty()) {
            JLabel empty = new JLabel("No transactions yet. Deposits and withdrawals will appear here.");
            empty.setFont(new Font("SansSerif", Font.PLAIN, 14));
            empty.setForeground(LoginPage.MUTED_TEXT);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            empty.setBorder(new EmptyBorder(30, 0, 30, 0));
            body.add(empty);
        } else {
            body.add(createTableScroll(transactions));
        }
        body.add(Box.createVerticalStrut(18));

        JButton backButton = LoginPage.createButton("Back to Dashboard", true);
        backButton.addActionListener(e -> LoginPage.navigate(this, new HomePage(account)));
        body.add(backButton);

        getRootPane().setDefaultButton(backButton);
        setContentPane(LoginPage.createPageBackground(LoginPage.wrapInCard(body)));
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
    }

    private JScrollPane createTableScroll(List<LoginPage.Transaction> transactions) {
        String[] columns = {"Date / Time", "Type", "Amount", "Balance After"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (int i = transactions.size() - 1; i >= 0; i--) {   // newest first
            LoginPage.Transaction t = transactions.get(i);
            String sign = t.getType().equals("Deposit") ? "+ " : "- ";
            model.addRow(new Object[]{
                    t.getTimestamp().format(TIME_FORMAT),
                    t.getType(),
                    sign + LoginPage.formatMoney(t.getAmount()),
                    LoginPage.formatMoney(t.getBalanceAfter())});
        }

        JTable table = new JTable(model);
        table.setFont(new Font("SansSerif", Font.PLAIN, 13));
        table.setRowHeight(30);
        table.setShowVerticalLines(false);
        table.setGridColor(LoginPage.LAVENDER);
        table.setSelectionBackground(LoginPage.LAVENDER);
        table.setSelectionForeground(LoginPage.DEEP_PURPLE);
        table.getColumnModel().getColumn(0).setPreferredWidth(190);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);

        DefaultTableCellRenderer headerRenderer = new DefaultTableCellRenderer();
        headerRenderer.setBackground(LoginPage.DEEP_PURPLE);
        headerRenderer.setForeground(Color.WHITE);
        headerRenderer.setFont(new Font("SansSerif", Font.BOLD, 13));
        headerRenderer.setBorder(new EmptyBorder(0, 8, 0, 8));
        table.getTableHeader().setDefaultRenderer(headerRenderer);
        table.getTableHeader().setReorderingAllowed(false);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(new LineBorder(LoginPage.LAVENDER, 1));
        scroll.getViewport().setBackground(Color.WHITE);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setPreferredSize(new Dimension(540, 250));
        return scroll;
    }
}