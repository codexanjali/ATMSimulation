public final class SessionSecurity {

    public enum Result { SUCCESS, INCORRECT, LOCKED }

    public static final int MAX_ATTEMPTS = 3;
    public static final String LOCKED_MESSAGE =
            "Session locked after 3 incorrect PIN attempts. Restart the application to try again.";

    private static int failedAttempts = 0;
    private static boolean locked = false;

    private SessionSecurity() { }

    public static synchronized boolean isLocked() {
        return locked;
    }

    public static synchronized int attemptsRemaining() {
        return Math.max(0, MAX_ATTEMPTS - failedAttempts);
    }

    /**
     * Checks a PIN and updates the consecutive-failure counter.
     * A null account (unknown card number at login) counts as a failed attempt.
     */
    public static synchronized Result verify(LoginPage.Account account, String pin) {
        if (locked) {
            return Result.LOCKED;
        }
        if (account != null && account.pinMatches(pin)) {
            failedAttempts = 0;
            return Result.SUCCESS;
        }
        failedAttempts++;
        if (failedAttempts >= MAX_ATTEMPTS) {
            locked = true;
            return Result.LOCKED;
        }
        return Result.INCORRECT;
    }

    /** Message for a failed result. 'what' is e.g. "PIN" or "card number or PIN". */
    public static synchronized String failureMessage(Result result, String what) {
        if (result == Result.LOCKED) {
            return LOCKED_MESSAGE;
        }
        int left = attemptsRemaining();
        return "Incorrect " + what + ". " + left + (left == 1 ? " attempt" : " attempts")
                + " left before the session locks.";
    }
}