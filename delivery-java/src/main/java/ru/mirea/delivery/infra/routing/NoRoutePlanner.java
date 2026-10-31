package ru.mirea.delivery.infra.routing;

import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.domain.GeoPoint;
import ru.mirea.delivery.error.ExternalServiceException;
import ru.mirea.delivery.port.RoadRoute;
import ru.mirea.delivery.port.RoutePlanner;

import java.util.List;

/** Маршрутизатор не настроен ({@code OSRM_URL} пуст): подбор и прогноз — по прямой. */
public final class NoRoutePlanner implements RoutePlanner {
    private static ExternalServiceException notConfigured() {
        return new ExternalServiceException("маршрутизатор не настроен", Maps.of("dependency", "osrm"));
    }

    @Override
    public List<Double> roadDistances(List<GeoPoint> from, GeoPoint to) {
        throw notConfigured();
    }

    @Override
    public RoadRoute route(GeoPoint from, GeoPoint to) {
        throw notConfigured();
    }
}
