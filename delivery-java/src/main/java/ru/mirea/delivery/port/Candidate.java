package ru.mirea.delivery.port;

import ru.mirea.delivery.domain.Courier;

/** Выбранный для предложения курьер (строка заблокирована до конца транзакции). */
public record Candidate(Courier courier, double distanceMeters) {
}
