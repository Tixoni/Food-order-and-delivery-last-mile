package ru.mirea.delivery;

import ru.mirea.delivery.app.CourierService;
import ru.mirea.delivery.app.DeliveryEvents;
import ru.mirea.delivery.app.DispatchService;
import ru.mirea.delivery.app.Routing;
import ru.mirea.delivery.app.TrackingService;
import ru.mirea.delivery.common.Log;
import ru.mirea.delivery.config.AppConfig;
import ru.mirea.delivery.http.Authorizer;
import ru.mirea.delivery.http.HttpApi;
import ru.mirea.delivery.http.Metrics;
import ru.mirea.delivery.http.auth.InternalTokenGuard;
import ru.mirea.delivery.http.auth.JwksTokenVerifier;
import ru.mirea.delivery.http.auth.TokenVerifier;
import ru.mirea.delivery.http.auth.UnverifiedTokenVerifier;
import ru.mirea.delivery.http.controller.AdminCouriersController;
import ru.mirea.delivery.http.controller.CourierController;
import ru.mirea.delivery.http.controller.HealthController;
import ru.mirea.delivery.http.controller.InternalDeliveryController;
import ru.mirea.delivery.http.controller.TrackingController;
import ru.mirea.delivery.infra.background.PeriodicTask;
import ru.mirea.delivery.infra.history.BatchedLocationHistory;
import ru.mirea.delivery.infra.messaging.KafkaMessageBroker;
import ru.mirea.delivery.infra.messaging.OutboxRelay;
import ru.mirea.delivery.infra.persistence.Database;
import ru.mirea.delivery.infra.persistence.SqlDeliveryRepository;
import ru.mirea.delivery.infra.redis.RedisPositionCache;
import ru.mirea.delivery.infra.routing.NoRoutePlanner;
import ru.mirea.delivery.infra.routing.OsrmRoutePlanner;
import ru.mirea.delivery.lastmile.PlannerCli;
import ru.mirea.delivery.port.RoutePlanner;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Запуск: без аргументов — сервис доставки; {@code plan <задача.json> [--json]}
 * — планировщик «последней мили» из командной строки.
 *
 * Здесь собирается граф объектов: инфраструктура → порты → сценарии →
 * контроллеры. Остальные классы не знают, какая реализация порта им
 * досталась, — поэтому сценарии проверяются без базы и брокера.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("plan")) {
            System.exit(PlannerCli.run(Arrays.copyOfRange(args, 1, args.length)));
            return;
        }
        prepareLoopbackSockets();
        AppConfig config = AppConfig.fromEnv(System.getenv());
        Log.configure("delivery", Log.Level.parse(config.logLevel()));
        Log log = Log.root();
        Clock clock = Clock.systemUTC();

        Database database = new Database(config.database());
        SqlDeliveryRepository repository = new SqlDeliveryRepository(database.dataSource());
        RedisPositionCache positions = new RedisPositionCache(config.redis(), config.locations().positionTtlSeconds());
        BatchedLocationHistory history = new BatchedLocationHistory(database.dataSource(),
                config.locations().maxBuffer());
        RoutePlanner planner = config.routing().url() != null
                ? new OsrmRoutePlanner(config.routing().url(), config.routing().timeoutMs())
                : new NoRoutePlanner();
        Routing routing = new Routing(planner, repository);
        DispatchService dispatch = new DispatchService(repository, routing, config.dispatch(), clock);
        CourierService couriers = new CourierService(repository, positions, history, dispatch, routing, clock);
        TrackingService tracking = new TrackingService(repository, positions, routing);

        TokenVerifier verifier = config.auth().disabled()
                ? new UnverifiedTokenVerifier() : new JwksTokenVerifier(config.auth());
        if (config.auth().disabled()) {
            log.warn("проверка токенов выключена (AUTH_DISABLED) — только для тестов");
        }
        Metrics metrics = new Metrics(HttpApi.SERVICE);
        HttpApi api = new HttpApi(List.of(
                new HealthController(database::readiness, metrics),
                new InternalDeliveryController(dispatch),
                new CourierController(couriers),
                new TrackingController(tracking),
                new AdminCouriersController(tracking)),
                new Authorizer(verifier, new InternalTokenGuard(config.internalToken())), metrics);

        KafkaMessageBroker broker = new KafkaMessageBroker(config.kafka());
        OutboxRelay relay = new OutboxRelay(database.dataSource(), broker, DeliveryEvents.TOPIC,
                config.outbox().batchSize());
        Log timeouts = Log.of("offer-timeouts");
        List<PeriodicTask> background = List.of(
                new PeriodicTask("outbox", Duration.ofMillis(config.outbox().pollIntervalMs()), relay::tick),
                new PeriodicTask("offer-timeouts", Duration.ofMillis(config.dispatch().sweepIntervalMs()), () -> {
                    int expired = dispatch.expireOffers(20);
                    if (expired > 0) {
                        timeouts.info("предложения истекли, заказ предложен следующим", "expired", expired);
                    }
                }),
                new PeriodicTask("location-history", Duration.ofMillis(config.locations().flushIntervalMs()),
                        history::flush));

        api.start(config.port());
        log.info("сервис запущен", "port", config.port(), "implementation", "java",
                "routing", config.routing().url() != null ? "osrm" : "по прямой");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.stop();
            background.forEach(PeriodicTask::close);
            history.flush();
            broker.close();
            positions.close();
            database.close();
            log.info("сервис остановлен");
        }, "shutdown"));
    }

    /**
     * На Windows сервер JDK открывает внутренний канал через сокет Unix в
     * каталоге профиля пользователя. Если в пути профиля есть кириллица,
     * сокет не создаётся и сервер не стартует. Поэтому, если каталог не
     * задан явно, берётся системный с путём латиницей.
     */
    private static void prepareLoopbackSockets() {
        String property = "jdk.net.unixdomain.tmpdir";
        if (System.getProperty(property) != null || !System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        for (String candidate : new String[] {System.getenv("SystemRoot") + "/Temp", "C:/Users/Public"}) {
            java.io.File directory = new java.io.File(candidate);
            if (directory.isDirectory() && directory.canWrite()) {
                System.setProperty(property, candidate);
                return;
            }
        }
    }
}
