"""
Маршрут курьера: оценка, построение «ближайшим соседом», улучшение 2-opt.

Маршрут — список остановок `{"order": id, "kind": "pickup"|"dropoff", "node": узел}`.
Курьер выходит из своей точки в момент 0. На точке выдачи он ждёт,
пока заказ будет готов; на точке вручения — начала окна клиента.
Опоздание к концу окна — нарушение, его величина считается в минутах.

Ограничения маршрута:
    — каждый заказ забирается и вручается ровно один раз, забор — раньше вручения;
    — на руках одновременно не больше вместимости курьера;
    — все точки достижимы по графу;
    — маршрут укладывается в смену.

Сложность (n — число остановок, n = 2·m для m заказов):
    evaluate_route         O(n)    — один проход с моделированием времени
    nearest_neighbor_route O(n²)   — n шагов, на каждом перебор до n кандидатов
    two_opt                O(n³) на проход: O(n²) пар (i, j), каждая оценивается
                           за O(n). В классической задаче коммивояжёра выигрыш
                           перестановки считается за O(1) и проход стоит O(n²),
                           но с окнами и порядком «забрал → вручил» время сдвигается
                           по всему хвосту маршрута, поэтому нужна полная оценка.
"""

import math

INF = math.inf
EPS = 1e-9


def travel_minutes(meters, courier):
    return meters / 1000 / courier["speed_kmh"] * 60


def evaluate_route(stops, courier, ctx):
    """
    Проиграть маршрут во времени. Возвращает словарь:
    valid (ограничения соблюдены), lateness (суммарное опоздание, мин),
    distance (м), travel (мин в пути), finish (мин), visits, problems.
    """
    matrix, orders, service = ctx["matrix"], ctx["orders"], ctx["service"]
    node, now, load = courier["node"], 0.0, 0
    distance = travel = lateness = 0.0
    picked, onboard, visits, problems = set(), set(), [], []

    for stop in stops:
        order = orders[stop["order"]]
        meters = matrix[node].get(stop["node"], INF)
        if meters == INF:
            problems.append(f"{order['id']}: точка недостижима")
            break
        minutes = travel_minutes(meters, courier)
        distance += meters
        travel += minutes
        now += minutes
        arrival = now

        if stop["kind"] == "pickup":
            if order["id"] in picked:
                problems.append(f"{order['id']}: забран дважды")
            now = max(now, order["ready"])
            load += order["size"]
            if load > courier["capacity"]:
                problems.append(f"{order['id']}: перегруз ({load} > {courier['capacity']})")
            picked.add(order["id"])
            onboard.add(order["id"])
            started = now
            now += service["pickup"]
        else:
            if order["id"] not in onboard:
                problems.append(f"{order['id']}: вручение раньше забора")
            window_start, window_end = order["window"]
            now = max(now, window_start)
            if now > window_end + EPS:
                lateness += now - window_end
            load -= order["size"]
            onboard.discard(order["id"])
            started = now
            now += service["dropoff"]

        visits.append({
            "order": order["id"],
            "kind": stop["kind"],
            "node": stop["node"],
            "arrival": arrival,
            "start": started,
            "departure": now,
        })
        node = stop["node"]

    if onboard and not problems:
        problems.append(f"не вручены: {', '.join(sorted(onboard))}")
    if now > courier["shift_minutes"] + EPS:
        problems.append(f"маршрут дольше смены ({now:.0f} > {courier['shift_minutes']} мин)")
    return {
        "valid": not problems,
        "lateness": lateness,
        "distance": distance,
        "travel": travel,
        "finish": now,
        "visits": visits,
        "problems": problems,
    }


def route_key(evaluation):
    """Порядок сравнения маршрутов: допустимый лучше, потом меньше опоздание, потом короче."""
    return (
        0 if evaluation["valid"] else 1,
        round(evaluation["lateness"], 6),
        round(evaluation["distance"], 3),
    )


def nearest_neighbor_route(courier, order_ids, ctx):
    """
    Жадное построение маршрута — «ближайший сосед» по времени начала
    обслуживания (вариант Соломона для задач с окнами).

    На каждом шаге кандидаты — забор любого ещё не взятого заказа, если
    хватает вместимости, и вручение любого заказа на руках. Выбирается
    тот, где обслуживание начнётся раньше всех (с учётом дороги, готовности
    еды и начала окна клиента); при равенстве — у кого раньше конец окна.
    Сложность O(n²).
    """
    matrix, orders, service = ctx["matrix"], ctx["orders"], ctx["service"]
    node, now, load = courier["node"], 0.0, 0
    waiting = list(order_ids)
    onboard = []
    route = []

    while waiting or onboard:
        best = None
        for order_id in waiting:
            order = orders[order_id]
            if load + order["size"] > courier["capacity"]:
                continue
            meters = matrix[node].get(order["pickup_node"], INF)
            if meters == INF:
                continue
            start = max(now + travel_minutes(meters, courier), order["ready"])
            key = (start, order["window"][1])
            if best is None or key < best[0]:
                best = (key, order_id, "pickup", order["pickup_node"], start)
        for order_id in onboard:
            order = orders[order_id]
            meters = matrix[node].get(order["dropoff_node"], INF)
            if meters == INF:
                continue
            start = max(now + travel_minutes(meters, courier), order["window"][0])
            key = (start, order["window"][1])
            if best is None or key < best[0]:
                best = (key, order_id, "dropoff", order["dropoff_node"], start)
        if best is None:
            # Остаток не взять: перегруз или недостижимые точки — оценка маршрута это покажет.
            break

        _, order_id, kind, stop_node, start = best
        route.append({"order": order_id, "kind": kind, "node": stop_node})
        node = stop_node
        if kind == "pickup":
            waiting.remove(order_id)
            onboard.append(order_id)
            load += orders[order_id]["size"]
            now = start + service["pickup"]
        else:
            onboard.remove(order_id)
            load -= orders[order_id]["size"]
            now = start + service["dropoff"]
    return route


def two_opt(stops, courier, ctx, max_passes=20):
    """
    Локальное улучшение 2-opt: разворот участка маршрута между позициями
    i и j. Перестановка принимается, если маршрут остаётся допустимым и
    становится лучше по `route_key` (меньше опоздание, затем короче).
    Проходы повторяются, пока есть улучшение (не больше `max_passes`).
    """
    best = list(stops)
    best_evaluation = evaluate_route(best, courier, ctx)
    improved, passes = True, 0
    while improved and passes < max_passes:
        improved = False
        passes += 1
        for i in range(len(best) - 1):
            for j in range(i + 1, len(best)):
                candidate = best[:i] + best[i:j + 1][::-1] + best[j + 1:]
                evaluation = evaluate_route(candidate, courier, ctx)
                if evaluation["valid"] and route_key(evaluation) < route_key(best_evaluation):
                    best, best_evaluation = candidate, evaluation
                    improved = True
    return best, best_evaluation, passes
