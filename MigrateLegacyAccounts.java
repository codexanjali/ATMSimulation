import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One-off, explicitly run import of a legacy atm_data.txt into MySQL.
 *
 *   java -cp "out;lib/*" MigrateLegacyAccounts [path\to\atm_data.txt]           (dry run: writes nothing)
 *   java -cp "out;lib/*" MigrateLegacyAccounts [path\to\atm_data.txt] --apply   (imports)
 *
 * - The text file is only read, never modified, moved or deleted.
 * - Existing PIN hashes and transaction timestamps are preserved (stored as salt:hash, same as new accounts).
 * - A card number that already exists in MySQL is skipped; nothing in MySQL is overwritten, so re-running is safe.
 * - Each account (row + its transactions + daily totals) is imported in its own transaction: all or nothing.
 * - ATM cash is NOT changed by the migration.
 */
public class MigrateLegacyAccounts {

    private static final class Txn {
        String type; BigDecimal amount; LocalDateTime time; BigDecimal balanceAfter;
    }

    private static final class Legacy {
        String card, name, storedHash; BigDecimal balance;
        final List<Txn> txns = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    public static void main(String[] args) {
        boolean apply = false;
        Path file = Paths.get(System.getProperty("user.dir"), "atm_data.txt");
        for (String arg : args) {
            if (arg.equals("--apply")) {
                apply = true;
            } else if (arg.equals("--dry-run")) {
                apply = false;
            } else {
                file = Paths.get(arg);
            }
        }
        System.out.println((apply ? "APPLY" : "DRY RUN (nothing will be written; add --apply to import)")
                + " - source: " + file.toAbsolutePath());

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("Cannot read the file: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            List<String> missing = Database.findMissingColumns();
            if (!missing.isEmpty()) {
                System.out.println("Schema is missing " + missing + ". Run schema.sql first.");
                System.exit(1);
            }
        } catch (SQLException e) {
            System.out.println("Database connection failed: " + e.getMessage());
            System.exit(1);
        }

        List<Legacy> accounts = parse(lines);
        int imported = 0, skippedExisting = 0, skippedInvalid = 0, failed = 0, txCount = 0;

        for (Legacy a : accounts) {
            String label = "..." + (a.card == null ? "?" : a.card.substring(Math.max(0, a.card.length() - 4)));
            if (!a.errors.isEmpty()) {
                skippedInvalid++;
                System.out.println("SKIP invalid  " + label + ": " + String.join("; ", a.errors));
                continue;
            }
            try {
                if (Database.cardExists(a.card)) {
                    skippedExisting++;
                    System.out.println("SKIP existing " + label + " (already in MySQL, left untouched)");
                    continue;
                }
                if (apply) {
                    importAccount(a);
                }
                imported++;
                txCount += a.txns.size();
                System.out.println((apply ? "IMPORTED     " : "WOULD IMPORT ") + label + " - "
                        + a.txns.size() + " transaction(s), balance " + a.balance.toPlainString());
            } catch (SQLException e) {
                failed++;
                System.out.println("FAILED       " + label + " (rolled back): " + e.getMessage());
            }
        }
        System.out.println("---- " + (apply ? "Imported" : "Would import") + ": " + imported + " account(s), "
                + txCount + " transaction(s) | skipped existing: " + skippedExisting
                + " | skipped invalid: " + skippedInvalid + " | failed: " + failed);
        if (!apply) {
            System.out.println("Dry run only. Re-run with --apply to import.");
        }
        System.exit(failed == 0 ? 0 : 2);
    }

    /** Line formats: ACCOUNT|card|name(URL-encoded)|salt(Base64)|hash(Base64)|balance and TXN|card|type|amount|ISO time|balanceAfter */
    private static List<Legacy> parse(List<String> lines) {
        Map<String, Legacy> byCard = new LinkedHashMap<>();
        List<Legacy> all = new ArrayList<>();
        List<String> orphanErrors = new ArrayList<>();
        int n = 0;
        for (String line : lines) {
            n++;
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] p = line.split("\\|", -1);
            try {
                if (p.length == 6 && p[0].equals("ACCOUNT")) {
                    Legacy a = new Legacy();
                    a.card = p[1];
                    if (byCard.containsKey(p[1])) {
                        byCard.get(p[1]).errors.add("duplicate ACCOUNT line " + n + " in file");
                        continue;
                    }
                    byCard.put(p[1], a);
                    all.add(a);
                    if (!p[1].matches("\\d{12}")) {
                        a.errors.add("line " + n + ": card number is not 12 digits");
                    }
                    a.name = URLDecoder.decode(p[2], StandardCharsets.UTF_8).trim();
                    if (a.name.isEmpty() || a.name.length() > 100) {
                        a.errors.add("line " + n + ": invalid name");
                    }
                    byte[] salt = Base64.getDecoder().decode(p[3]);
                    byte[] hash = Base64.getDecoder().decode(p[4]);
                    if (salt.length != 16 || hash.length != 32) {
                        a.errors.add("line " + n + ": PIN salt/hash has unexpected length");
                    }
                    a.storedHash = p[3] + ":" + p[4];
                    a.balance = new BigDecimal(p[5]);
                    if (a.balance.signum() < 0 || a.balance.stripTrailingZeros().scale() > 2) {
                        a.errors.add("line " + n + ": invalid balance");
                    }
                    a.balance = a.balance.setScale(2, java.math.RoundingMode.DOWN);
                } else if (p.length == 6 && p[0].equals("TXN")) {
                    Legacy a = byCard.get(p[1]);
                    if (a == null) {
                        orphanErrors.add("line " + n + ": TXN before its ACCOUNT (ignored)");
                        continue;
                    }
                    Txn t = new Txn();
                    t.type = p[2];
                    t.amount = new BigDecimal(p[3]);
                    t.time = LocalDateTime.parse(p[4]).truncatedTo(ChronoUnit.SECONDS);
                    t.balanceAfter = new BigDecimal(p[5]);
                    if (!(t.type.equals("Deposit") || t.type.equals("Withdrawal")) || t.amount.signum() <= 0
                            || t.amount.stripTrailingZeros().scale() > 2 || t.balanceAfter.signum() < 0) {
                        a.errors.add("line " + n + ": invalid transaction");
                    }
                    t.amount = t.amount.setScale(2, java.math.RoundingMode.DOWN);
                    t.balanceAfter = t.balanceAfter.setScale(2, java.math.RoundingMode.DOWN);
                    a.txns.add(t);
                } else {
                    orphanErrors.add("line " + n + ": unrecognised line (ignored)");
                }
            } catch (RuntimeException e) {
                Legacy owner = p.length > 1 ? byCard.get(p[1]) : null;
                String msg = "line " + n + ": cannot parse (" + e.getClass().getSimpleName() + ")";
                if (owner != null) {
                    owner.errors.add(msg);
                } else {
                    orphanErrors.add(msg);
                }
            }
        }
        for (String e : orphanErrors) {
            System.out.println("WARN " + e);
        }
        return all;
    }

    private static void importAccount(Legacy a) throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                long id;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO accounts (full_name, card_number, pin, balance) VALUES (?, ?, ?, ?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, a.name);
                    ps.setString(2, a.card);
                    ps.setString(3, a.storedHash);
                    ps.setBigDecimal(4, a.balance);
                    ps.executeUpdate();
                    try (java.sql.ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("No account_id returned");
                        }
                        id = keys.getLong(1);
                    }
                }
                Map<java.sql.Date, BigDecimal> daily = new TreeMap<>();
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO transactions "
                        + "(account_id, transaction_type, amount, status, description, balance_after, created_at) "
                        + "VALUES (?, ?, ?, 'SUCCESS', 'Migrated from atm_data.txt', ?, ?)")) {
                    for (Txn t : a.txns) {
                        ps.setLong(1, id);
                        ps.setString(2, t.type.toUpperCase(java.util.Locale.ROOT));
                        ps.setBigDecimal(3, t.amount);
                        ps.setBigDecimal(4, t.balanceAfter);
                        ps.setTimestamp(5, Timestamp.valueOf(t.time));
                        ps.addBatch();
                        if (t.type.equals("Withdrawal")) {
                            daily.merge(java.sql.Date.valueOf(t.time.toLocalDate()), t.amount, BigDecimal::add);
                        }
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO daily_withdrawals "
                        + "(account_id, withdrawal_date, total_amount) VALUES (?, ?, ?)")) {
                    for (Map.Entry<java.sql.Date, BigDecimal> e : daily.entrySet()) {
                        ps.setLong(1, id);
                        ps.setDate(2, e.getKey());
                        ps.setBigDecimal(3, e.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // connection is closed right after; original error is what matters
                }
                throw e;
            }
        }
    }
}