"""
Граф улиц и кратчайшие пути.

Граф — словарь смежности: `{узел: [(сосед, длина_м), ...]}`. Рёбра
бывают односторонними (улицы с односторонним движением), поэтому
расстояние «туда» и «обратно» может различаться.

Сложность (V — узлы, E — рёбра, P — точки задачи):
    build_graph        O(V + E)
    dijkstra           O((V + E) log V) — двоичная куча `heapq`
    shortest_path      O(V) — восстановление пути по массиву предков
    nearest_node       O(V) — перебор узлов
    distance_matrix    O(P · (V + E) log V) — Дейкстра из каждой точки задачи
"""

import heapq
import math

from ..geo import distance_meters

INF = math.inf


def build_graph(nodes, edges):
    """
    `nodes` — `{id: [широта, долгота]}`, `edges` — `[[из, в, длина_м], ...]`;
    четвёртый элемент `"oneway"` делает ребро односторонним.
    """
    graph = {node: [] for node in nodes}
    for edge in edges:
        start, end, length = edge[0], edge[1], float(edge[2])
        if start not in graph or end not in graph:
            raise ValueError(f"ребро {start}–{end} ссылается на неизвестный узел")
        if length < 0:
            raise ValueError(f"ребро {start}–{end}: отрицательная длина")
        graph[start].append((end, length))
        oneway = len(edge) > 3 and edge[3] == "oneway"
        if not oneway:
            graph[end].append((start, length))
    return graph


def dijkstra(graph, source):
    """
    Кратчайшие расстояния от `source` до всех узлов.

    Возвращает `(расстояния, предки)`; недостижимых узлов в словаре
    расстояний нет. Из кучи достаётся узел с наименьшей оценкой; устаревшие
    записи (узел уже закреплён) пропускаются — так обходится без операции
    «уменьшить ключ», которой у `heapq` нет.
    """
    distances = {source: 0.0}
    previous = {source: None}
    done = set()
    heap = [(0.0, source)]
    while heap:
        distance, node = heapq.heappop(heap)
        if node in done:
            continue
        done.add(node)
        for neighbor, length in graph[node]:
            candidate = distance + length
            if candidate < distances.get(neighbor, INF):
                distances[neighbor] = candidate
                previous[neighbor] = node
                heapq.heappush(heap, (candidate, neighbor))
    return distances, previous


def shortest_path(previous, target):
    """Путь от источника до `target` по массиву предков; `[]` — узел недостижим."""
    if target not in previous:
        return []
    path = []
    node = target
    while node is not None:
        path.append(node)
        node = previous[node]
    path.reverse()
    return path


def nearest_node(nodes, point):
    """Ближайший к точке узел графа — «привязка» адреса к улице."""
    best, best_distance = None, INF
    target = {"latitude": point[0], "longitude": point[1]}
    for node, (lat, lon) in nodes.items():
        distance = distance_meters({"latitude": lat, "longitude": lon}, target)
        if distance < best_distance:
            best, best_distance = node, distance
    return best


def distance_matrix(graph, points):
    """
    Расстояния по графу между всеми точками задачи: `{из: {в: метры}}`.
    Пара без пути получает `INF`. Дейкстра запускается по разу из каждой
    точки — дешевле, чем Флойд — Уоршелл `O(V³)`, потому что точек задачи
    намного меньше, чем узлов графа.
    """
    unique = list(dict.fromkeys(points))
    matrix = {}
    for source in unique:
        distances, _ = dijkstra(graph, source)
        matrix[source] = {target: distances.get(target, INF) for target in unique}
    return matrix
