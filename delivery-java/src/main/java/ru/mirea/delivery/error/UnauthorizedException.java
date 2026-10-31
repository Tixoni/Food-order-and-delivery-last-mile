package ru.mirea.delivery.error;

/** Нет входа или токен недействителен — 401. */
public class UnauthorizedException extends AppException {
    public UnauthorizedException(String message) {
        super(message, null);
    }

    @Override
    public int httpStatus() {
        return 401;
    }

    @Override
    public String code() {
        return "UNAUTHORIZED";
    }
}
