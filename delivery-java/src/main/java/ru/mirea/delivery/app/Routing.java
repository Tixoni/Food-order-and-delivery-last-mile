package ru.mirea.delivery.app;

import ru.mirea.delivery.config.AppConfig;
import ru.mirea.delivery.domain.Delivery;
import ru.mirea.delivery.domain.DeliveryStatus;
import ru.mirea.delivery.port.CandidateInfo;
import ru.mirea.delivery.port.Candidates;
import ru.mirea.delivery.port.DeliveryRepository;
import ru.mirea.delivery.port.RoadRoute;
import ru.mirea.delivery.port.RoutePlanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Маршруты для подбора и для экранов (DLV-3, DLV-6).
 *
 * Отказ маршрутизатора здесь гасится и никогда не доходит до курьера или
 * клиента (REL-2): подбор идёт по прямой, экран получает доставку без
 * линии маршрута.
 */
public final class Routing {
    /** Сколько ближайших по прямой кандидатов ранжируется по маршруту. */
    static final int RANKED = 10;

    private final RoutePlanner planner;
    private final DeliveryRepository deliveries;

    public Routing(RoutePlanner planner, DeliveryRepository deliveries) {
        this.planner = planner;
        this.deliveries = deliveries;
    }

    private record Ranked(String courierId, double seconds, double straight) {
    }

    /**
     * Порядок кандидатов по времени в пути до ресторана — по дорогам и
     * с учётом транспорта: пеший в 500 м доедет дольше велосипедиста
     * в полутора километрах. Время считает сам курьер — по своему виду
     * транспорта. {@code null} — ранжировать нечего или маршрутизатор
     * недоступен: предложение получит ближайший по прямой.
     */
    public List<String> rankCandidates(String deliveryId, AppConfig.Dispatch settings) {
        Candidates found = deliveries.candidates(deliveryId, settings.radiusMeters(), settings.freshSeconds(), RANKED);
        if (found == null || found.candidates().size() < 2) {
            return null;
        }
        List<Double> roads;
        try {
            roads = planner.roadDistances(found.candidates().stream().map(CandidateInfo::position).toList(),
                    found.pickup());
        } catch (RuntimeException routerDown) {
            return null;
        }
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < found.candidates().size(); i++) {
            CandidateInfo candidate = found.candidates().get(i);
            Double road = i < roads.size() ? roads.get(i) : null;
            // Без дороги до ресторана — в конец очереди, но не из подбора.
            double seconds = road == null ? Double.POSITIVE_INFINITY : candidate.courier().travelSeconds(road);
            ranked.add(new Ranked(candidate.courier().id(), seconds, candidate.distanceMeters()));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::seconds).thenComparingDouble(Ranked::straight));
        return ranked.stream().map(Ranked::courierId).toList();
    }

    /**
     * Маршрут «ресторан → клиент»: сохранённый, а если его ещё нет —
     * посчитать и сохранить. Недоступен маршрутизатор — следующий
     * просмотр экрана попробует снова.
     */
    public RoadRoute deliveryRoute(Delivery delivery) {
        RoadRoute stored = deliveries.routeOf(delivery.id());
        if (stored != null || delivery.status() == DeliveryStatus.DELIVERED
                || delivery.status() == DeliveryStatus.CANCELLED) {
            return stored;
        }
        try {
            RoadRoute route = planner.route(delivery.pickup(), delivery.dropoff());
            if (route != null) {
                deliveries.saveRoute(delivery.id(), route);
            }
            return route;
        } catch (RuntimeException routerDown) {
            return null;
        }
    }
}
