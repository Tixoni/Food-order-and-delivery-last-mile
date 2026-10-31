"""
Генератор тестового набора для планировщика «последней мили».

Граф — сетка улиц 12×12 вокруг Китай-города с шагом ~300 м:
    — длина улицы — расстояние по прямой × 1.00–1.25 (улицы не прямые);
    — «река» между 6-м и 7-м рядами, через неё два моста (столбцы 2 и 9);
    — часть улиц односторонние, часть перекрёстков без проезда —
      но граф остаётся сильно связным (из любого узла можно доехать в любой);
    — отдельный узел «island» без рёбер — недостижимая точка (закрытая территория).

Курьеры — 6 (пешие, вело, авто), заказы — 20 из пяти ресторанов. Среди
заказов нарочно есть граничные случаи: доставка на «остров» (недостижима)
и заказ для банкета размером 8 (больше вместимости любого курьера).

Запуск: python make_sample.py > moscow_sample.json — генерация детерминирована (seed 37).
"""

import json
import math
import random
import sys

SEED = 37
ROWS = COLS = 12
STEP_M = 300
CENTER = (55.7520, 37.6175)
RIVER_BETWEEN = (5, 6)
BRIDGES = (2, 9)


def node_id(row, col):
    return f"n{row:02d}{col:02d}"


def haversine(a, b):
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    d_lat, d_lon = lat2 - lat1, math.radians(b[1] - a[1])
    h = math.sin(d_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(d_lon / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(h))


def make_nodes():
    d_lat = STEP_M / 111_195
    d_lon = STEP_M / (111_195 * math.cos(math.radians(CENTER[0])))
    top = CENTER[0] + d_lat * (ROWS - 1) / 2
    left = CENTER[1] - d_lon * (COLS - 1) / 2
    nodes = {}
    for row in range(ROWS):
        for col in range(COLS):
            nodes[node_id(row, col)] = [round(top - row * d_lat, 6), round(left + col * d_lon, 6)]
    return nodes


def reachable(adjacency, start):
    seen, stack = {start}, [start]
    while stack:
        node = stack.pop()
        for neighbor in adjacency[node]:
            if neighbor not in seen:
                seen.add(neighbor)
                stack.append(neighbor)
    return seen


def strongly_connected(nodes, edges):
    forward = {node: [] for node in nodes}
    backward = {node: [] for node in nodes}
    for edge in edges:
        forward[edge[0]].append(edge[1])
        backward[edge[1]].append(edge[0])
        if len(edge) < 4:
            forward[edge[1]].append(edge[0])
            backward[edge[0]].append(edge[1])
    start = next(iter(nodes))
    return len(reachable(forward, start)) == len(nodes) == len(reachable(backward, start))


def make_edges(nodes, rng):
    edges = []
    for row in range(ROWS):
        for col in range(COLS):
            here = node_id(row, col)
            if col + 1 < COLS:
                there = node_id(row, col + 1)
                edges.append([here, there, round(haversine(nodes[here], nodes[there]) * rng.uniform(1.0, 1.25), 1)])
            if row + 1 < ROWS:
                if (row, row + 1) == RIVER_BETWEEN and col not in BRIDGES:
                    continue
                there = node_id(row + 1, col)
                edges.append([here, there, round(haversine(nodes[here], nodes[there]) * rng.uniform(1.0, 1.25), 1)])

    # Односторонние улицы: в чётных рядах — на восток, в нечётных — на запад.
    for edge in edges:
        same_row = edge[0][1:3] == edge[1][1:3]
        if same_row and rng.random() < 0.2:
            row = int(edge[0][1:3])
            if row % 2 == 1:
                edge[0], edge[1] = edge[1], edge[0]
            edge.append("oneway")
            if not strongly_connected(nodes, edges):
                edge.pop()

    # Перекрытые участки — без потери связности.
    rng.shuffle(edges)
    kept = list(edges)
    for edge in edges:
        if rng.random() < 0.08:
            trial = [e for e in kept if e is not edge]
            if strongly_connected(nodes, trial):
                kept = trial
    kept.sort(key=lambda e: (e[0], e[1]))
    return kept


def main():
    rng = random.Random(SEED)
    nodes = make_nodes()
    edges = make_edges(nodes, rng)
    nodes["island"] = [55.7489, 37.6290]  # закрытая территория: рёбер нет

    restaurants = [nodes[node_id(r, c)] for r, c in ((1, 2), (3, 8), (8, 3), (9, 9), (5, 5))]
    vehicles = ["foot", "foot", "bike", "bike", "bike", "car"]
    couriers = []
    for index, vehicle in enumerate(vehicles, start=1):
        start = nodes[node_id(rng.randrange(ROWS), rng.randrange(COLS))]
        couriers.append({"id": f"K{index}", "vehicle": vehicle, "start": start, "shift_minutes": 180})

    orders = []
    for index in range(1, 19):
        ready = rng.randrange(0, 31)
        window_start = ready + rng.randrange(15, 31)
        orders.append({
            "id": f"Z{index:02d}",
            "pickup": rng.choice(restaurants),
            "dropoff": nodes[node_id(rng.randrange(ROWS), rng.randrange(COLS))],
            "ready": ready,
            "window": [window_start, window_start + rng.randrange(25, 46)],
            "size": rng.choice([1, 1, 1, 2]),
        })
    orders.append({
        "id": "Z19", "pickup": restaurants[0], "dropoff": nodes["island"],
        "ready": 10, "window": [30, 70], "size": 1,
    })
    orders.append({
        "id": "Z20", "pickup": restaurants[1], "dropoff": nodes[node_id(4, 4)],
        "ready": 20, "window": [45, 90], "size": 8,
    })

    problem = {
        "name": "Центр Москвы: 12×12 улиц, река с двумя мостами, 6 курьеров, 20 заказов",
        "vehicles": {
            "foot": {"speed_kmh": 5, "capacity": 2},
            "bike": {"speed_kmh": 14, "capacity": 3},
            "car": {"speed_kmh": 20, "capacity": 6},
        },
        "service": {"pickup": 2, "dropoff": 3},
        "nodes": nodes,
        "edges": edges,
        "couriers": couriers,
        "orders": orders,
    }
    json.dump(problem, sys.stdout, ensure_ascii=False, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
