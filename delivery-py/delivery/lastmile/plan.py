"""
Полный расчёт плана и отчёт.

    подготовка   граф, привязка точек к узлам, матрица расстояний (Дейкстра)
    распределение жадно по сроку, маршруты «ближайшим соседом»
    улучшение    2-opt для маршрута каждого курьера
    проверка     окна, вместимость, порядок «забрал → вручил», смена
"""

import time

from . import problem as problems
from .assign import assign_orders
from .routes import evaluate_route, nearest_neighbor_route, two_opt


def make_plan(problem):
    timings = {}
    started = time.perf_counter()
    ctx = problems.prepare(problem)
    timings["prepare_ms"] = (time.perf_counter() - started) * 1000

    started = time.perf_counter()
    assigned, _, unassigned = assign_orders(ctx)
    timings["assign_ms"] = (time.perf_counter() - started) * 1000

    started = time.perf_counter()
    routes = []
    for courier in ctx["couriers"]:
        order_ids = assigned[courier["id"]]
        greedy = nearest_neighbor_route(courier, order_ids, ctx)
        greedy_evaluation = evaluate_route(greedy, courier, ctx)
        improved, evaluation, passes = two_opt(greedy, courier, ctx)
        routes.append({
            "courier": courier["id"],
            "vehicle": courier["vehicle"],
            "orders": order_ids,
            "stops": improved,
            "greedy_distance": greedy_evaluation["distance"],
            "evaluation": evaluation,
            "passes": passes,
        })
    timings["improve_ms"] = (time.perf_counter() - started) * 1000

    totals = {
        "orders": len(ctx["orders"]),
        "assigned": sum(len(route["orders"]) for route in routes),
        "unassigned": len(unassigned),
        "distance_m": sum(route["evaluation"]["distance"] for route in routes),
        "greedy_distance_m": sum(route["greedy_distance"] for route in routes),
        "lateness_min": sum(route["evaluation"]["lateness"] for route in routes),
        "graph_nodes": len(ctx["graph"]),
        "graph_edges": sum(len(edges) for edges in ctx["graph"].values()),
    }
    return {"routes": routes, "unassigned": unassigned, "totals": totals, "timings": timings}


def _clock(minutes):
    return f"+{int(minutes) // 60}:{int(minutes) % 60:02d}"


def format_report(plan):
    lines = []
    totals = plan["totals"]
    lines.append(
        f"Граф: {totals['graph_nodes']} узлов, {totals['graph_edges']} направленных рёбер. "
        f"Заказов: {totals['orders']}, распределено: {totals['assigned']}, "
        f"без курьера: {totals['unassigned']}."
    )
    for route in plan["routes"]:
        evaluation = route["evaluation"]
        lines.append("")
        lines.append(
            f"Курьер {route['courier']} ({route['vehicle']}): заказов {len(route['orders'])}, "
            f"путь {evaluation['distance'] / 1000:.2f} км "
            f"(жадный {route['greedy_distance'] / 1000:.2f} км), "
            f"финиш {_clock(evaluation['finish'])}, опоздание {evaluation['lateness']:.0f} мин, "
            f"проходов 2-opt: {route['passes']}"
        )
        for visit in evaluation["visits"]:
            action = "забрать " if visit["kind"] == "pickup" else "вручить "
            wait = visit["start"] - visit["arrival"]
            lines.append(
                f"    {_clock(visit['arrival'])}  {action}{visit['order']:<6} "
                f"узел {visit['node']:<8}" + (f" ожидание {wait:.0f} мин" if wait >= 1 else "")
            )
    if plan["unassigned"]:
        lines.append("")
        lines.append("Без курьера:")
        for item in plan["unassigned"]:
            lines.append(f"    {item['order']}: {item['reason']}")
    lines.append("")
    saved = totals["greedy_distance_m"] - totals["distance_m"]
    lines.append(
        f"Итого: {totals['distance_m'] / 1000:.2f} км, 2-opt сократил путь на {saved / 1000:.2f} км, "
        f"опоздание {totals['lateness_min']:.0f} мин."
    )
    timings = plan["timings"]
    lines.append(
        f"Время расчёта: подготовка {timings['prepare_ms']:.1f} мс, распределение "
        f"{timings['assign_ms']:.1f} мс, улучшение {timings['improve_ms']:.1f} мс."
    )
    return "\n".join(lines)
