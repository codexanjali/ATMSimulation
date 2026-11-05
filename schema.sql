-- Banking ATM Simulation Database Schema
-- MySQL 8.4+
-- Safe to rerun: does not drop tables or reset existing records.

CREATE DATABASE IF NOT EXISTS banking_atm;
USE banking_atm;

-- 1. Accounts
CREATE TABLE IF NOT EXISTS accounts (
    account_id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    card_number CHAR(12) NOT NULL UNIQUE,
    pin VARCHAR(255) NOT NULL,
    balance DECIMAL(12,2) NOT NULL DEFAULT 500.00,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Legacy login table
-- Retained for compatibility with existing data.
-- New authentication should use accounts.import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class Database {

    // =====================================================
    // DATABASE CONNECTION
    // =====================================================

    private static final String URL = System.getenv().getOrDefault(
            "ATM_DB_URL",
            "jdbc:mysql://localhost:3306/banking_atm"
    );

    private static final String USER =
            System.getenv().getOrDefault("ATM_DB_USER", "root");

    private static final String PASSWORD =
            System.getenv("ATM_DB_PASSWORD");

    private static final int PBKDF2_ITERATIONS = 65536;

    private static final SecureRandom RANDOM = new SecureRandom();

    static final BigDecimal OPENING_BALANCE =
            new BigDecimal("500.00");

    static final int ATM_ID = 1;

    private static final String[][] REQUIRED_COLUMNS = {
        {"accounts", "account_id"},
        {"accounts", "full_name"},
        {"accounts", "card_number"},
        {"accounts", "pin"},
        {"accounts", "balance"},
        {"accounts", "created_at"},

        {"transactions", "transaction_id"},
        {"transactions", "account_id"},
        {"transactions", "transaction_type"},
        {"transactions", "amount"},
        {"transactions", "status"},
        {"transactions", "balance_after"},
        {"transactions", "created_at"},

        {"daily_withdrawals", "account_id"},
        {"daily_withdrawals", "withdrawal_date"},
        {"daily_withdrawals", "total_amount"},

        {"atm_settings", "atm_id"},
        {"atm_settings", "available_cash"},
        {"atm_settings", "daily_withdrawal_limit"},

        {"login", "login_id"},
        {"login", "account_id"},
        {"login", "cardnum"},
        {"login", "status"},
        {"login", "failure_reason"},
        {"login", "attempted_at"}
    };

    public static Connection getConnection() throws SQLException {

        if (PASSWORD == null || PASSWORD.isBlank()) {
            throw new SQLException(
                    "ATM_DB_PASSWORD is not set. "
                    + "Set the MySQL password in PowerShell."
            );
        }

        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private static <T> T inTransaction(Work<T> work)
            throws SQLException {

        try (Connection connection = getConnection()) {

            connection.setAutoCommit(false);

            try {
                T result = work.run(connection);
                connection.commit();
                return result;

            } catch (SQLException | RuntimeException e) {

                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }

                throw e;
            }
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now()
                .truncatedTo(ChronoUnit.MILLIS);
    }

    static BigDecimal normalize(BigDecimal amount) {

        if (amount == null
                || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 2) {
            return null;
        }

        return amount.setScale(2);
    }

    // =====================================================
    // LOGIN AUDIT
    // =====================================================

    
public static void recordLoginAttempt(
            String cardNumber,
            Long accountId,
            boolean success,
            String reason
    ) throws SQLException {

        String sql =
                "INSERT INTO login "
                + "(cardnum, account_id, account_name, status, failure_reason) "
                + "VALUES (?, ?, ?, ?, ?)";

        String safeCard = cardNumber == null
                ? ""
                : cardNumber.trim();

        if (safeCard.length() > 12) {
            safeCard = safeCard.substring(0, 12);
        }

        String safeReason = reason == null || reason.isBlank()
                ? null
                : reason.trim();

        if (safeReason != null && safeReason.length() > 50) {
            safeReason = safeReason.substring(0, 50);
        }

        try (Connection connection = getConnection()) {

            String accountName = null;

            // Get the registered name for this account.
            if (accountId != null) {
                String nameSql =
                        "SELECT full_name FROM accounts WHERE account_id = ?";

                try (PreparedStatement nameStatement =
                            connection.prepareStatement(nameSql)) {

                    nameStatement.setLong(1, accountId);

                    try (ResultSet result = nameStatement.executeQuery()) {
                        if (result.next()) {
                            accountName = result.getString("full_name");
                        }
                    }
                }
            }

            // Save the login attempt and registered name.
            try (PreparedStatement statement =
                        connection.prepareStatement(sql)) {

                statement.setString(1, safeCard);

                if (accountId == null) {
                    statement.setNull(2, java.sql.Types.INTEGER);
                } else {
                    statement.setLong(2, accountId);
                }

                statement.setString(3, accountName);
                statement.setString(4, success ? "SUCCESS" : "FAILED");
                statement.setString(5, success ? null : safeReason);

                statement.executeUpdate();
            }
        }
    }


    // =====================================================
    // PIN HASHING
    // =====================================================

    /** PLAINTEXT mode (demo only): the PIN is stored exactly as entered. */
    static String encodePin(String pin) throws SQLException {

        if (pin == null) {
            throw new SQLException("PIN is missing.");
        }

        return pin;
    }

    /** Compares the entered PIN with the stored PIN (constant-time comparison). */
    static boolean pinMatchesStored(
            String pin,
            String stored
    ) throws SQLException {

        if (pin == null || stored == null) {
            return false;
        }

        return MessageDigest.isEqual(
                pin.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                stored.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private static byte[] hashPin(
            String pin,
            byte[] salt
    ) throws SQLException {

        PBEKeySpec spec = new PBEKeySpec(
                pin.toCharArray(),
                salt,
                PBKDF2_ITERATIONS,
                256
        );

        try {
            return SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();

        } catch (GeneralSecurityException e) {
            throw new SQLException(
                    "Could not securely hash the PIN.",
                    e
            );

        } finally {
            spec.clearPassword();
        }
    }

    // =====================================================
    // ACCOUNT MANAGEMENT
    // =====================================================

    public static void createAccount(
            String fullName,
            String cardNumber,
            String pin
    ) throws SQLException {

        String sql =
                "INSERT INTO accounts "
                + "(full_name, card_number, pin, balance) "
                + "VALUES (?, ?, ?, ?)";

        String storedHash = encodePin(pin);

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, fullName);
            statement.setString(2, cardNumber);
            statement.setString(3, storedHash);
            statement.setBigDecimal(4, OPENING_BALANCE);

            statement.executeUpdate();
        }
    }

    static boolean cardExists(String cardNumber)
            throws SQLException {

        String sql =
                "SELECT 1 FROM accounts WHERE card_number = ?";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, cardNumber);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    static LoginPage.Account findAccountByCard(
            String cardNumber
    ) throws SQLException {

        String sql =
                "SELECT account_id, full_name, balance "
                + "FROM accounts WHERE card_number = ?";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, cardNumber);

            try (ResultSet result = statement.executeQuery()) {

                if (result.next()) {
                    return new LoginPage.Account(
                            result.getLong("account_id"),
                            result.getString("full_name"),
                            cardNumber,
                            result.getBigDecimal("balance")
                    );
                }

                return null;
            }
        }
    }

    static BigDecimal getBalance(long accountId)
            throws SQLException {

        try (Connection connection = getConnection()) {
            return queryBalance(connection, accountId, "");
        }
    }

    static boolean verifyPin(
            long accountId,
            String pin
    ) throws SQLException {

        String sql =
                "SELECT pin FROM accounts WHERE account_id = ?";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, accountId);

            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && pinMatchesStored(
                                pin,
                                result.getString("pin")
                        );
            }
        }
    }

    static boolean changePin(
            long accountId,
            String currentPin,
            String newPin
    ) throws SQLException {

        return inTransaction(connection -> {

            String storedHash;

            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT pin FROM accounts "
                            + "WHERE account_id = ? FOR UPDATE"
                    )) {

                statement.setLong(1, accountId);

                try (ResultSet result = statement.executeQuery()) {

                    if (!result.next()) {
                        return false;
                    }

                    storedHash = result.getString("pin");
                }
            }

            if (!pinMatchesStored(currentPin, storedHash)) {
                return false;
            }

            String sql =
                    "UPDATE accounts SET pin = ? "
                    + "WHERE account_id = ?";

            try (PreparedStatement statement =
                    connection.prepareStatement(sql)) {

                statement.setString(1, encodePin(newPin));
                statement.setLong(2, accountId);

                return statement.executeUpdate() == 1;
            }
        });
    }

    // =====================================================
    // DEPOSITS
    // =====================================================

    static LoginPage.Transaction deposit(
            long accountId,
            BigDecimal rawAmount
    ) throws SQLException {

        BigDecimal amount = normalize(rawAmount);

        if (amount == null) {
            return null;
        }

        return inTransaction(connection -> {

            queryBalance(connection, accountId, " FOR UPDATE");
            queryAtmCash(connection, " FOR UPDATE");

            update(
                    connection,
                    "UPDATE accounts SET balance = balance + ? "
                    + "WHERE account_id = ?",
                    amount,
                    accountId
            );

            update(
                    connection,
                    "UPDATE atm_settings "
                    + "SET available_cash = available_cash + ? "
                    + "WHERE atm_id = ?",
                    amount,
                    ATM_ID
            );

            BigDecimal after =
                    queryBalance(connection, accountId, "");

            return logTransaction(
                    connection,
                    accountId,
                    "DEPOSIT",
                    amount,
                    now(),
                    after
            );
        });
    }

    // =====================================================
    // WITHDRAWALS
    // =====================================================

    static LoginPage.WithdrawalStatus checkWithdrawal(
            long accountId,
            BigDecimal amount
    ) throws SQLException {

        try (Connection connection = getConnection()) {
            return evaluate(connection, accountId, amount, false);
        }
    }

    static WithdrawResult withdraw(
            long accountId,
            BigDecimal rawAmount
    ) throws SQLException {

        BigDecimal amount = normalize(rawAmount);

        if (amount == null) {
            return new WithdrawResult(
                    LoginPage.WithdrawalStatus.INVALID_AMOUNT,
                    null
            );
        }

        return inTransaction(connection -> {

            LoginPage.WithdrawalStatus status =
                    evaluate(connection, accountId, amount, true);

            if (status != LoginPage.WithdrawalStatus.OK) {
                connection.rollback();

                return new WithdrawResult(status, null);
            }

            LocalDateTime time = now();

            requireOneRow(update(
                    connection,
                    "UPDATE accounts SET balance = balance - ? "
                    + "WHERE account_id = ? AND balance >= ?",
                    amount,
                    accountId,
                    amount
            ));

            requireOneRow(update(
                    connection,
                    "UPDATE atm_settings "
                    + "SET available_cash = available_cash - ? "
                    + "WHERE atm_id = ? AND available_cash >= ?",
                    amount,
                    ATM_ID,
                    amount
            ));

            requireOneRow(update(
                    connection,
                    "UPDATE daily_withdrawals "
                    + "SET total_amount = total_amount + ? "
                    + "WHERE account_id = ? AND withdrawal_date = ?",
                    amount,
                    accountId,
                    sqlDate(time)
            ));

            BigDecimal after =
                    queryBalance(connection, accountId, "");

            LoginPage.Transaction transaction =
                    logTransaction(
                            connection,
                            accountId,
                            "WITHDRAWAL",
                            amount,
                            time,
                            after
                    );

            return new WithdrawResult(
                    LoginPage.WithdrawalStatus.OK,
                    transaction
            );
        });
    }

    public static final class WithdrawResult {
        public final LoginPage.WithdrawalStatus status;
        public final LoginPage.Transaction transaction;

        WithdrawResult(
                LoginPage.WithdrawalStatus status,
                LoginPage.Transaction transaction
        ) {
            this.status = status;
            this.transaction = transaction;
        }
    }

    private static LoginPage.WithdrawalStatus evaluate(
            Connection connection,
            long accountId,
            BigDecimal rawAmount,
            boolean lock
    ) throws SQLException {

        BigDecimal amount = normalize(rawAmount);

        if (amount == null) {
            return LoginPage.WithdrawalStatus.INVALID_AMOUNT;
        }

        String forUpdate = lock ? " FOR UPDATE" : "";

        BigDecimal balance =
                queryBalance(connection, accountId, forUpdate);

        if (amount.compareTo(balance) > 0) {
            return LoginPage.WithdrawalStatus.INSUFFICIENT_BALANCE;
        }

        LocalDateTime time = now();

        if (lock) {
            update(
                    connection,
                    "INSERT IGNORE INTO daily_withdrawals "
                    + "(account_id, withdrawal_date, total_amount) "
                    + "VALUES (?, ?, 0.00)",
                    accountId,
                    sqlDate(time)
            );
        }

        BigDecimal withdrawn =
                queryWithdrawnToday(connection, accountId, forUpdate);

        BigDecimal dailyLimit = getDailyLimit(connection);

        if (withdrawn.add(amount).compareTo(dailyLimit) > 0) {
            return LoginPage.WithdrawalStatus.DAILY_LIMIT_EXCEEDED;
        }

        BigDecimal atmCash = queryAtmCash(connection, forUpdate);

        if (amount.compareTo(atmCash) > 0) {
            return LoginPage.WithdrawalStatus.INSUFFICIENT_ATM_CASH;
        }

        return LoginPage.WithdrawalStatus.OK;
    }

    private static BigDecimal getDailyLimit(
            Connection connection
    ) throws SQLException {

        String sql =
                "SELECT daily_withdrawal_limit "
                + "FROM atm_settings WHERE atm_id = ?";

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setInt(1, ATM_ID);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    throw new SQLException(
                            "ATM settings row is missing."
                    );
                }

                BigDecimal limit =
                        result.getBigDecimal("daily_withdrawal_limit");

                return limit == null
                        ? new BigDecimal("10000.00")
                        : limit;
            }
        }
    }

    static BigDecimal getWithdrawnToday(long accountId)
            throws SQLException {

        try (Connection connection = getConnection()) {
            return queryWithdrawnToday(connection, accountId, "");
        }
    }

    // =====================================================
    // TRANSACTION HISTORY
    // =====================================================

    static List<LoginPage.Transaction> getTransactions(
            long accountId
    ) throws SQLException {

        String sql =
                "SELECT transaction_type, amount, balance_after, created_at "
                + "FROM transactions WHERE account_id = ? "
                + "ORDER BY created_at, transaction_id";

        List<LoginPage.Transaction> transactions =
                new ArrayList<>();

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, accountId);

            try (ResultSet result = statement.executeQuery()) {

                while (result.next()) {

                    Timestamp timestamp =
                            result.getTimestamp("created_at");

                    transactions.add(
                            new LoginPage.Transaction(
                                    result.getString("transaction_type"),
                                    result.getBigDecimal("amount"),
                                    timestamp.toLocalDateTime(),
                                    result.getBigDecimal("balance_after")
                            )
                    );
                }
            }
        }

        return transactions;
    }

    static LoginPage.Transaction getLastTransaction(
            long accountId
    ) throws SQLException {

        String sql =
                "SELECT transaction_type, amount, balance_after, created_at "
                + "FROM transactions WHERE account_id = ? "
                + "ORDER BY created_at DESC, transaction_id DESC LIMIT 1";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, accountId);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return null;
                }

                return new LoginPage.Transaction(
                        result.getString("transaction_type"),
                        result.getBigDecimal("amount"),
                        result.getTimestamp("created_at").toLocalDateTime(),
                        result.getBigDecimal("balance_after")
                );
            }
        }
    }

    // =====================================================
    // SQL HELPERS
    // =====================================================

    private static Object sqlDate(LocalDateTime time) {
        return java.sql.Date.valueOf(time.toLocalDate());
    }

    private static BigDecimal queryBalance(
            Connection connection,
            long accountId,
            String forUpdate
    ) throws SQLException {

        String sql =
                "SELECT balance FROM accounts WHERE account_id = ?"
                + forUpdate;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, accountId);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    throw new SQLException(
                            "Account " + accountId + " not found."
                    );
                }

                return result.getBigDecimal("balance");
            }
        }
    }

    private static BigDecimal queryWithdrawnToday(
            Connection connection,
            long accountId,
            String forUpdate
    ) throws SQLException {

        String sql =
                "SELECT total_amount FROM daily_withdrawals "
                + "WHERE account_id = ? AND withdrawal_date = ?"
                + forUpdate;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, accountId);
            statement.setDate(
                    2,
                    java.sql.Date.valueOf(LocalDate.now())
            );

            try (ResultSet result = statement.executeQuery()) {

                return result.next()
                        ? result.getBigDecimal("total_amount")
                        : new BigDecimal("0.00");
            }
        }
    }

    private static BigDecimal queryAtmCash(
            Connection connection,
            String forUpdate
    ) throws SQLException {

        String sql =
                "SELECT available_cash FROM atm_settings "
                + "WHERE atm_id = ?" + forUpdate;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setInt(1, ATM_ID);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    throw new SQLException(
                            "ATM settings row " + ATM_ID
                            + " is missing in atm_settings."
                    );
                }

                return result.getBigDecimal("available_cash");
            }
        }
    }

    private static LoginPage.Transaction logTransaction(
            Connection connection,
            long accountId,
            String type,
            BigDecimal amount,
            LocalDateTime time,
            BigDecimal balanceAfter
    ) throws SQLException {

        String sql =
                "INSERT INTO transactions "
                + "(account_id, transaction_type, amount, status, "
                + "balance_after, created_at) "
                + "VALUES (?, ?, ?, 'SUCCESS', ?, ?)";

        requireOneRow(update(
                connection,
                sql,
                accountId,
                type.toUpperCase(Locale.ROOT),
                amount,
                balanceAfter,
                Timestamp.valueOf(time)
        ));

        return new LoginPage.Transaction(
                type,
                amount,
                time,
                balanceAfter
        );
    }

    private static int update(
            Connection connection,
            String sql,
            Object... parameters
    ) throws SQLException {

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }

            return statement.executeUpdate();
        }
    }

    private static void requireOneRow(int rows)
            throws SQLException {

        if (rows != 1) {
            throw new SQLException(
                    "Unexpected update count " + rows
                    + "; transaction rolled back."
            );
        }
    }

    // =====================================================
    // SCHEMA DIAGNOSTICS
    // =====================================================

    static List<String> findMissingColumns()
            throws SQLException {

        Set<String> present = new HashSet<>();

        String sql =
                "SELECT TABLE_NAME, COLUMN_NAME "
                + "FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE()";

        try (Connection connection = getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                present.add(
                        (result.getString(1) + "."
                        + result.getString(2))
                        .toLowerCase(Locale.ROOT)
                );
            }
        }

        List<String> missing = new ArrayList<>();

        for (String[] column : REQUIRED_COLUMNS) {

            String name = (
                    column[0] + "." + column[1]
            ).toLowerCase(Locale.ROOT);

            if (!present.contains(name)) {
                missing.add(column[0] + "." + column[1]);
            }
        }

        return missing;
    }

    // =====================================================
    // CONNECTION TEST
    // =====================================================

    public static void main(String[] args) {

        try (Connection connection = getConnection()) {

            System.out.println(
                    "MySQL database connected successfully!"
            );

            List<String> missing = findMissingColumns();

            if (missing.isEmpty()) {
                System.out.println("Schema check: OK");
            } else {
                System.out.println(
                        "Schema check: MISSING columns -> " + missing
                );
            }

        } catch (SQLException e) {

            System.err.println(
                    "Database connection failed: "
                    + e.getMessage()
            );

            e.printStackTrace();
        }
    }
}.
CREATE TABLE IF NOT EXISTS login (
    cardnum VARCHAR(12),
    pin VARCHAR(4)
);

-- 3. Transaction history
CREATE TABLE IF NOT EXISTS transactions (
    transaction_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id INT NULL,
    transaction_type ENUM(
        'LOGIN',
        'DEPOSIT',
        'WITHDRAWAL',
        'BALANCE_ENQUIRY',
        'PIN_CHANGE'
    ) NOT NULL,
    amount DECIMAL(12,2) NULL,
    status ENUM('SUCCESS', 'FAILED') NOT NULL,
    description VARCHAR(255) NULL,
    balance_after DECIMAL(12,2) NULL,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_transactions_account_id (account_id),
    INDEX idx_transactions_created_at (created_at)
);

-- 4. Daily withdrawal totals
CREATE TABLE IF NOT EXISTS daily_withdrawals (
    account_id INT NOT NULL,
    withdrawal_date DATE NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    PRIMARY KEY (account_id, withdrawal_date)
);

-- 5. ATM settings
-- IMPORTANT: Matches the existing table structure.
CREATE TABLE IF NOT EXISTS atm_settings (
    atm_id INT NOT NULL PRIMARY KEY,
    available_cash DECIMAL(12,2) NOT NULL,
    daily_withdrawal_limit DECIMAL(12,2) NOT NULL
);

-- Insert default settings only if ATM 1 does not already exist.
-- Existing ATM cash and withdrawal limits will not be overwritten.
INSERT IGNORE INTO atm_settings (
    atm_id,
    available_cash,
    daily_withdrawal_limit
) VALUES (
    1,
    100000.00,
    10000.00
);
