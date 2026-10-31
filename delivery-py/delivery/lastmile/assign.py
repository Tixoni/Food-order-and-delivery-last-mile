"""
Распределение заказов по курьерам.

Точная постановка — задача маршрутизации транспорта с окнами, вместимостью
и парами «забрал → вручил» (PDPTW). Она содержит задачу упаковки в
контейнеры (заказы по вместимости курьеров) и задачу коммивояжёра
(порядок точек), обе NP-трудные, поэтому точный перебор растёт
экспоненциально, и для десятков заказов в реальном времени нужна эвристика.

Эвристика — жадная, «по сроку»:
    1. Заказы сортируются по концу окна доставки (самые срочные — первыми),
       как в правиле «ранний срок — первым» (EDF).
    2. Для заказа перебираются курьеры. Для каждого строится маршрут из
       уже назначенных ему заказов плюс нового — «ближайшим соседом».
    3. Заказ получает курьер, у которого маршрут остаётся допустимым,
       без опозданий, и время в пути растёт меньше всего.
    4. Если такого курьера нет — заказ остаётся нераспределённым с причиной
       (перегруз, недостижимая точка, не успеть в окно); его отдают оператору
       или переносят в следующий раунд планирования.

Сложность: O(m log m) на сортировку и O(m · k · n²) на назначение, где
m — заказов, k — курьеров, n — остановок в маршруте курьера (n ≤ 2m).
"""

from .routes import INF, evaluate_route, nearest_neighbor_route


def _reason(order, couriers, ctx):
    """Почему заказ не назначен ни одному курьеру."""
    matrix = ctx["matrix"]
    # Без этой проверки all() по пустому списку истинно, и заказ без курьеров
    # получал причину «перегруз» — оператор искал бы не ту проблему.
    if not couriers:
        return "нет курьеров на смене"
    if all(order["size"] > courier["capacity"] for courier in couriers):
        return "перегруз: заказ больше вместимости любого курьера"
    if matrix[order["pickup_node"]].get(order["dropoff_node"], INF) == INF:
        return "точка вручения недостижима от точки выдачи"
    if all(matrix[courier["node"]].get(order["pickup_node"], INF) == INF for courier in couriers):
        return "точка выдачи недостижима ни для одного курьера"
    return "ни один курьер не успевает в окно доставки"


def assign_orders(ctx):
    """
    Возвращает `(назначения, оценки, нераспределённые)`:
    назначения — `{курьер: [заказы]}`, оценки — `{курьер: оценка маршрута}`,
    нераспределённые — `[{"order": id, "reason": причина}]`.
    """
    couriers, orders = ctx["couriers"], ctx["orders"]
    assigned = {courier["id"]: [] for courier in couriers}
    evaluations = {courier["id"]: evaluate_route([], courier, ctx) for courier in couriers}
    unassigned = []

    queue = sorted(orders.values(), key=lambda order: (order["window"][1], order["ready"], order["id"]))
    for order in queue:
        best = None
        for courier in couriers:
            if order["size"] > courier["capacity"]:
                continue
            candidate_ids = assigned[courier["id"]] + [order["id"]]
            route = nearest_neighbor_route(courier, candidate_ids, ctx)
            evaluation = evaluate_route(route, courier, ctx)
            if len(evaluation["visits"]) != 2 * len(candidate_ids):
                continue
            if not evaluation["valid"] or evaluation["lateness"] > 0:
                continue
            added = evaluation["travel"] - evaluations[courier["id"]]["travel"]
            if best is None or (added, evaluation["finish"]) < best[0]:
                best = ((added, evaluation["finish"]), courier["id"], evaluation)
        if best is None:
            unassigned.append({"order": order["id"], "reason": _reason(order, couriers, ctx)})
            continue
        _, courier_id, evaluation = best
        assigned[courier_id].append(order["id"])
        evaluations[courier_id] = evaluation
    return assigned, evaluations, unassigned
