# Сервис доставки на Java. Многоэтапная сборка: Maven и исходники остаются
# в первом образе, в итоговый попадает только собранный jar и JRE.

# ─────────── Этап 1: сборка ───────────
FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /build
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ─────────── Этап 2: выполнение ───────────
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=builder /build/target/delivery-java-all.jar ./delivery.jar

RUN useradd --system --uid 10001 delivery
USER delivery

EXPOSE 3004

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/3004 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '200' <&3"]

CMD ["java", "-jar", "/app/delivery.jar"]
