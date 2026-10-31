"""
Входные данные планирования: чтение JSON, проверка и подготовка.

Формат задачи:

    nodes     {id: [широта, долгота]}                 — перекрёстки
    edges     [[из, в, длина_м], [из, в, длина_м, "oneway"], ...]
    vehicles  {тип: {"speed_kmh": .., "capacity": ..}} — скорость и вместимость
    service   {"pickup": мин, "dropoff": мин}           — время на точке
    couriers  [{"id", "vehicle", "start": [ш, д], "shift_minutes"}]
    orders    [{"id", "pickup": [ш, д], "dropoff": [ш, д],
                "ready": мин, "window": [с, по], "size": ед.}]

Подготовка привязывает каждую точку к ближайшему узлу графа и считает
матрицу расстояний между точками задачи (Дейкстра из каждой точки).
"""

import json

from . import graph as graphs

DEFAULT_VEHICLES = {
    "foot": {"speed_kmh": 5, "capacity": 2},
    "bike": {"speed_kmh": 14, "capacity": 3},
    "car": {"speed_kmh": 20, "capacity": 6},
}
DEFAULT_SERVICE = {"pickup": 2, "dropoff": 3}


def load_problem(path):
    with open(path, encoding="utf-8") as source:
        return json.load(source)


def validate(problem):
    """Список ошибок данных; пустой — данные годятся."""
    problems = []
    vehicles = problem.get("vehicles") or DEFAULT_VEHICLES
    seen = set()
    for courier in problem.get("couriers", []):
        if courier.get("vehicle") not in vehicles:
            problems.append(f"курьер {courier.get('id')}: неизвестный транспорт {courier.get('vehicle')}")
        if courier.get("id") in seen:
            problems.append(f"повторяется идентификатор {courier.get('id')}")
        seen.add(courier.get("id"))
    for order in problem.get("orders", []):
        window = order.get("window") or []
        if len(window) != 2 or window[0] > window[1]:
            problems.append(f"заказ {order.get('id')}: окно доставки задано неверно")
        if not isinstance(order.get("size"), (int, float)) or order["size"] <= 0:
            problems.append(f"заказ {order.get('id')}: пустой заказ (размер должен быть больше 0)")
        if order.get("ready", 0) < 0:
            problems.append(f"заказ {order.get('id')}: время готовности раньше начала планирования")
        if order.get("id") in seen:
            problems.append(f"повторяется идентификатор {order.get('id')}")
        seen.add(order.get("id"))
    return problems


def prepare(problem):
    """
    Контекст расчёта: граф, привязка точек к узлам, матрица расстояний.
    Сложность — O(V + E) на граф, O((C + 2Z) · V) на привязку (C курьеров,
    Z заказов) и O(P · (V + E) log V) на матрицу.
    """
    errors = validate(problem)
    if errors:
        raise ValueError("; ".join(errors))
    nodes = problem["nodes"]
    graph = graphs.build_graph(nodes, problem["edges"])
    vehicles = problem.get("vehicles") or DEFAULT_VEHICLES
    service = problem.get("service") or DEFAULT_SERVICE

    couriers = []
    for courier in problem["couriers"]:
        couriers.append({
            **courier,
            "node": graphs.nearest_node(nodes, courier["start"]),
            "speed_kmh": vehicles[courier["vehicle"]]["speed_kmh"],
            "capacity": vehicles[courier["vehicle"]]["capacity"],
            "shift_minutes": courier.get("shift_minutes", 240),
        })
    orders = {}
    for order in problem["orders"]:
        orders[order["id"]] = {
            **order,
            "pickup_node": graphs.nearest_node(nodes, order["pickup"]),
            "dropoff_node": graphs.nearest_node(nodes, order["dropoff"]),
            "ready": order.get("ready", 0),
        }

    points = [c["node"] for c in couriers]
    for order in orders.values():
        points += [order["pickup_node"], order["dropoff_node"]]
    return {
        "graph": graph,
        "nodes": nodes,
        "couriers": couriers,
        "orders": orders,
        "service": service,
        "matrix": graphs.distance_matrix(graph, points),
    }
