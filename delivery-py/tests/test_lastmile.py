"""
Алгоритмы «последней мили»: граф и Дейкстра, маршрут, распределение,
полный план на тестовом наборе. Граничные случаи — из плана практики:
пустой заказ, недостижимая точка, перегруз курьера, окно, в которое не успеть.
"""

import json
from pathlib import Path

import pytest

from delivery.lastmile import graph, problem, routes
from delivery.lastmile.assign import assign_orders
from delivery.lastmile.plan import make_plan

SAMPLE = Path(__file__).resolve().parents[2] / "data" / "moscow_sample.json"

# Маленький город: «линейка» из пяти перекрёстков по 1 км и тупиковый «остров».
NODES = {
    "a": [55.700, 37.600],
    "b": [55.709, 37.600],
    "c": [55.718, 37.600],
    "d": [55.727, 37.600],
    "e": [55.736, 37.600],
    "island": [55.800, 37.700],
}
EDGES = [["a", "b", 1000], ["b", "c", 1000], ["c", "d", 1000], ["d", "e", 1000]]


def task(orders, couriers=None):
    return {
        "nodes": NODES,
        "edges": EDGES,
        "vehicles": {"bike": {"speed_kmh": 12, "capacity": 2}, "car": {"speed_kmh": 30, "capacity": 4}},
        "service": {"pickup": 0, "dropoff": 0},
        "couriers": couriers if couriers is not None else [
            {"id": "K1", "vehicle": "bike", "start": NODES["a"], "shift_minutes": 240}
        ],
        "orders": orders,
    }


def order(order_id, pickup, dropoff, window=(0, 240), ready=0, size=1):
    return {
        "id": order_id, "pickup": NODES[pickup], "dropoff": NODES[dropoff],
        "ready": ready, "window": list(window), "size": size,
    }


# ─────────────────────────── граф ───────────────────────────


def test_dijkstra_finds_shortest_path():
    g = graph.build_graph(NODES, EDGES + [["a", "e", 5000]])
    distances, previous = graph.dijkstra(g, "a")
    assert distances["e"] == 4000
    assert graph.shortest_path(previous, "e") == ["a", "b", "c", "d", "e"]


def test_oneway_street_changes_distance_back():
    g = graph.build_graph(NODES, [["a", "b", 1000, "oneway"], ["a", "c", 500], ["c", "b", 800]])
    there, _ = graph.dijkstra(g, "a")
    back, _ = graph.dijkstra(g, "b")
    assert there["b"] == 1000 and back["a"] == 1300


def test_unreachable_node_is_absent():
    g = graph.build_graph(NODES, EDGES)
    distances, previous = graph.dijkstra(g, "a")
    assert "island" not in distances
    assert graph.shortest_path(previous, "island") == []
    assert graph.distance_matrix(g, ["a", "island"])["a"]["island"] == graph.INF


def test_nearest_node_snaps_point_to_street():
    assert graph.nearest_node(NODES, [55.7095, 37.6001]) == "b"


def test_bad_edge_is_rejected():
    with pytest.raises(ValueError):
        graph.build_graph(NODES, [["a", "zzz", 10]])


# ─────────────────────────── маршрут ───────────────────────────


def test_route_keeps_pickup_before_dropoff_and_capacity():
    ctx = problem.prepare(task([order("Z1", "b", "e"), order("Z2", "c", "d"), order("Z3", "b", "c")]))
    courier = ctx["couriers"][0]
    route = routes.nearest_neighbor_route(courier, ["Z1", "Z2", "Z3"], ctx)
    evaluation = routes.evaluate_route(route, courier, ctx)
    assert evaluation["valid"], evaluation["problems"]
    for order_id in ("Z1", "Z2", "Z3"):
        kinds = [stop["kind"] for stop in route if stop["order"] == order_id]
        assert kinds == ["pickup", "dropoff"]
    load, peak = 0, 0
    for stop in route:
        load += 1 if stop["kind"] == "pickup" else -1
        peak = max(peak, load)
    assert peak <= courier["capacity"]


def test_evaluation_counts_lateness_and_waiting():
    # 12 км/ч — 5 минут на километр; вручение на «e» (4 км от выдачи) не раньше 20-й минуты.
    ctx = problem.prepare(task([order("Z1", "a", "e", window=(0, 10), ready=5)]))
    courier = ctx["couriers"][0]
    stops = [
        {"order": "Z1", "kind": "pickup", "node": "a"},
        {"order": "Z1", "kind": "dropoff", "node": "e"},
    ]
    evaluation = routes.evaluate_route(stops, courier, ctx)
    assert evaluation["visits"][0]["start"] == 5  # ждал готовности
    assert evaluation["lateness"] == pytest.approx(15)  # приехал на 25-й минуте, окно до 10-й


def test_dropoff_before_pickup_is_invalid():
    ctx = problem.prepare(task([order("Z1", "b", "c")]))
    stops = [
        {"order": "Z1", "kind": "dropoff", "node": "c"},
        {"order": "Z1", "kind": "pickup", "node": "b"},
    ]
    assert not routes.evaluate_route(stops, ctx["couriers"][0], ctx)["valid"]


def _stops_of(route):
    return sorted((stop["order"], stop["kind"]) for stop in route)


def test_two_opt_never_makes_route_worse():
    orders = [order("Z1", "e", "a"), order("Z2", "b", "d"), order("Z3", "c", "b")]
    car = {"id": "K1", "vehicle": "car", "start": NODES["c"], "shift_minutes": 240}
    ctx = problem.prepare(task(orders, [car]))
    courier = ctx["couriers"][0]
    greedy = routes.nearest_neighbor_route(courier, ["Z1", "Z2", "Z3"], ctx)
    before = routes.evaluate_route(greedy, courier, ctx)
    improved, after, _ = routes.two_opt(greedy, courier, ctx)
    assert after["valid"]
    assert routes.route_key(after) <= routes.route_key(before)
    assert _stops_of(improved) == _stops_of(greedy)


def test_empty_route_is_valid():
    ctx = problem.prepare(task([]))
    evaluation = routes.evaluate_route([], ctx["couriers"][0], ctx)
    assert evaluation["valid"] and evaluation["distance"] == 0


# ─────────────────────────── распределение ───────────────────────────


def test_overloaded_order_is_not_assigned():
    ctx = problem.prepare(task([order("Z1", "a", "b", size=5)]))
    _, _, unassigned = assign_orders(ctx)
    assert unassigned == [{"order": "Z1", "reason": "перегруз: заказ больше вместимости любого курьера"}]


def test_unreachable_dropoff_is_not_assigned():
    ctx = problem.prepare(task([{**order("Z1", "a", "b"), "dropoff": NODES["island"]}]))
    _, _, unassigned = assign_orders(ctx)
    assert unassigned[0]["reason"] == "точка вручения недостижима от точки выдачи"


def test_impossible_window_is_not_assigned():
    ctx = problem.prepare(task([order("Z1", "a", "e", window=(0, 5))]))
    _, _, unassigned = assign_orders(ctx)
    assert unassigned[0]["reason"] == "ни один курьер не успевает в окно доставки"


def test_order_goes_to_courier_who_adds_least_travel():
    couriers = [
        {"id": "far", "vehicle": "bike", "start": NODES["e"], "shift_minutes": 240},
        {"id": "near", "vehicle": "bike", "start": NODES["a"], "shift_minutes": 240},
    ]
    ctx = problem.prepare(task([order("Z1", "a", "b")], couriers))
    assigned, _, _ = assign_orders(ctx)
    assert assigned == {"far": [], "near": ["Z1"]}


def test_empty_order_is_rejected_on_input():
    with pytest.raises(ValueError, match="пустой заказ"):
        problem.prepare(task([order("Z1", "a", "b", size=0)]))


def test_bad_window_is_rejected_on_input():
    with pytest.raises(ValueError, match="окно"):
        problem.prepare(task([order("Z1", "a", "b", window=(30, 10))]))


# ─────────────────────────── весь план на тестовом наборе ───────────────────────────


@pytest.mark.skipif(not SAMPLE.exists(), reason="нет data/moscow_sample.json")
def test_sample_plan_is_consistent():
    plan = make_plan(json.loads(SAMPLE.read_text(encoding="utf-8")))
    seen = []
    for route in plan["routes"]:
        evaluation = route["evaluation"]
        assert evaluation["valid"], (route["courier"], evaluation["problems"])
        assert evaluation["lateness"] == 0
        seen += [stop["order"] for stop in route["stops"] if stop["kind"] == "dropoff"]
        assert route["evaluation"]["distance"] <= route["greedy_distance"] + 1e-6
    unassigned = {item["order"] for item in plan["unassigned"]}
    assert sorted(seen) == sorted(set(seen)), "каждый заказ вручается ровно один раз"
    assert set(seen) | unassigned == {f"Z{i:02d}" for i in range(1, 21)}
    assert unassigned == {"Z19", "Z20"}


# ─────────────────────────── найденные ошибки (docs/testing.md, журнал) ───────────────────────────


def test_no_couriers_gives_honest_reason():
    """Ошибка №1: без курьеров причина была «перегруз»."""
    ctx = problem.prepare(task([order("Z1", "a", "b")], couriers=[]))
    _, _, unassigned = assign_orders(ctx)
    assert unassigned == [{"order": "Z1", "reason": "нет курьеров на смене"}]


def test_zero_length_window_is_rejected_like_in_database():
    """Ошибка №3: окно [30, 30] планировщик принимал, а CHECK ck_deliveries_window — нет."""
    with pytest.raises(ValueError, match="окно"):
        problem.prepare(task([order("Z1", "a", "b", window=(30, 30))]))


@pytest.mark.skipif(not SAMPLE.exists(), reason="нет data/moscow_sample.json")
def test_cli_prints_report(capsys):
    from delivery.lastmile.__main__ import main

    assert main([str(SAMPLE)]) == 0
    report = capsys.readouterr().out
    assert "распределено: 18" in report and "Z20: перегруз" in report
    assert main([]) == 2
