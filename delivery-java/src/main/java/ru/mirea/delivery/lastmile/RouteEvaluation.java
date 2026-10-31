package ru.mirea.delivery.lastmile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Итог проигрывания маршрута: допустим ли он, опоздание, длина, время в
 * пути, финиш, посещения и найденные нарушения.
 */
public record RouteEvaluation(boolean valid, double lateness, double distance, double travel, double finish,
                              List<Visit> visits, List<String> problems) {

    /** Ключ сравнения: допустимый лучше, затем меньше опоздание, затем короче. */
    public record Key(int invalid, double lateness, double distance) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int byValid = Integer.compare(invalid, other.invalid);
            if (byValid != 0) {
                return byValid;
            }
            int byLateness = Double.compare(lateness, other.lateness);
            return byLateness != 0 ? byLateness : Double.compare(distance, other.distance);
        }
    }

    /**
     * Округление — как {@code round(x, n)} в Python (точное значение, к ближайшему
     * чётному): разница меньше миллиметра не считается улучшением.
     */
    public Key key() {
        return new Key(valid ? 0 : 1, round(lateness, 6), round(distance, 3));
    }

    static double round(double value, int digits) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }
}
