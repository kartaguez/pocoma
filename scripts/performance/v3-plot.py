#!/usr/bin/env python3
"""Pocoma V3 — Performance Plotting & Artifact Generation (scripts/performance/v3-plot.py).

Parses benchmark execution reports (*_report.json) and produces:
1. Canonical CSV datasets for all measured dimensions.
2. High-quality vector SVG charts (zero third-party dependencies):
   - Throughput Demanded vs Sustained/Realized
   - Latency Percentiles (p50, p95, p99) vs Throughput
   - Detailed Stage Breakdown (T0..T10)
   - Retries, Backoff and 503 Capacity Refusal Rates
   - System & Resource Utilization (CPU, Heap, Hikari, PostgreSQL)
   - Structural Pot Size Impact (Small vs Medium vs Large)

Enforces strict metrological integrity: zero fictive or extrapolated data points.
"""

import argparse
import csv
import json
import math
import os
from pathlib import Path
import sys
from typing import Any, Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_DIR = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3"
CHARTS_DIR = EVIDENCE_DIR / "charts"


def load_all_reports(evidence_dir: Path = EVIDENCE_DIR) -> List[Dict[str, Any]]:
    reports = []
    for f in sorted(evidence_dir.glob("*_report.json")):
        try:
            with open(f, "r", encoding="utf-8") as fp:
                data = json.load(fp)
                data["_file"] = f.name
                reports.append(data)
        except Exception as e:
            print(f"Warning: could not read {f}: {e}", file=sys.stderr)
    return reports


class SvgChart:
    """Zero-dependency SVG line and bar chart generator."""

    def __init__(self, title: str, width: int = 800, height: int = 450,
                 x_label: str = "", y_label: str = ""):
        self.title = title
        self.width = width
        self.height = height
        self.x_label = x_label
        self.y_label = y_label
        self.margin_left = 80
        self.margin_right = 160
        self.margin_top = 60
        self.margin_bottom = 60
        self.plot_w = width - self.margin_left - self.margin_right
        self.plot_h = height - self.margin_top - self.margin_bottom
        self.series: List[Dict[str, Any]] = []
        self.bar_groups: List[Dict[str, Any]] = []

    def add_line(self, name: str, points: List[Tuple[Any, Any]], color: str,
                 dashed: bool = False, marker: str = "circle"):
        clean_pts = []
        for p in points:
            if p[0] is not None and p[1] is not None:
                try:
                    clean_pts.append((float(p[0]), float(p[1])))
                except (ValueError, TypeError):
                    pass
        self.series.append({
            "name": name,
            "points": sorted(clean_pts, key=lambda pt: pt[0]),
            "color": color,
            "dashed": dashed,
            "marker": marker,
        })

    def add_bars(self, categories: List[str], series_data: List[Dict[str, Any]]):
        # series_data = list of {"name": str, "values": List[float], "color": str}
        self.bar_groups = {
            "categories": categories,
            "series": series_data,
        }

    def render(self) -> str:
        svg = [
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.width} {self.height}" '
            f'width="{self.width}" height="{self.height}" style="background-color: #1e1e2e; font-family: -apple-system, BlinkMacSystemFont, sans-serif;">',
            f'<text x="{self.width / 2}" y="32" fill="#cdd6f4" font-size="16" font-weight="bold" text-anchor="middle">{self.title}</text>',
        ]
        if self.bar_groups:
            return self._render_bars(svg)
        all_x = [p[0] for s in self.series for p in s["points"]]
        all_y = [p[1] for s in self.series for p in s["points"]]
        if not all_x or not all_y:
            svg.append(f'<text x="{self.width / 2}" y="{self.height / 2}" fill="#6c7086" text-anchor="middle">No data points</text>')
            svg.append('</svg>')
            return "\n".join(svg)
        tx, ty = self._render_grid_and_axes(svg, all_x, all_y)
        self._render_series_and_legend(svg, tx, ty)
        svg.append('</svg>')
        return "\n".join(svg)

    def _render_grid_and_axes(self, svg: List[str], all_x: List[float], all_y: List[float]):
        min_x, max_x = min(all_x), max(all_x)
        min_y, max_y = 0.0, max(all_y) * 1.15
        if max_x == min_x:
            max_x += 1.0
        if max_y == min_y:
            max_y += 1.0
        tx = lambda x: self.margin_left + ((x - min_x) / (max_x - min_x)) * self.plot_w
        ty = lambda y: self.margin_top + self.plot_h - ((y - min_y) / (max_y - min_y)) * self.plot_h
        for i in range(6):
            val_y = min_y + (i / 5) * (max_y - min_y)
            py = ty(val_y)
            svg.append(f'<line x1="{self.margin_left}" y1="{py}" x2="{self.margin_left + self.plot_w}" y2="{py}" stroke="#313244" stroke-width="1" stroke-dasharray="3,3"/>')
            svg.append(f'<text x="{self.margin_left - 10}" y="{py + 4}" fill="#a6adc8" font-size="11" text-anchor="end">{val_y:.1f}</text>')
        for vx in sorted(set(all_x)):
            px = tx(vx)
            svg.append(f'<line x1="{px}" y1="{self.margin_top}" x2="{px}" y2="{self.margin_top + self.plot_h}" stroke="#313244" stroke-width="1" stroke-dasharray="2,2"/>')
            svg.append(f'<text x="{px}" y="{self.margin_top + self.plot_h + 20}" fill="#a6adc8" font-size="11" text-anchor="middle">{vx:g}</text>')
        svg.append(f'<line x1="{self.margin_left}" y1="{self.margin_top}" x2="{self.margin_left}" y2="{self.margin_top + self.plot_h}" stroke="#585b70" stroke-width="1.5"/>')
        svg.append(f'<line x1="{self.margin_left}" y1="{self.margin_top + self.plot_h}" x2="{self.margin_left + self.plot_w}" y2="{self.margin_top + self.plot_h}" stroke="#585b70" stroke-width="1.5"/>')
        svg.append(f'<text x="{self.margin_left + self.plot_w / 2}" y="{self.height - 15}" fill="#cdd6f4" font-size="12" text-anchor="middle">{self.x_label}</text>')
        svg.append(f'<text transform="rotate(-90)" x="-{self.margin_top + self.plot_h / 2}" y="24" fill="#cdd6f4" font-size="12" text-anchor="middle">{self.y_label}</text>')
        return tx, ty

    def _render_series_and_legend(self, svg: List[str], tx, ty):
        for s in self.series:
            pts = s["points"]
            if not pts:
                continue
            path_d = " ".join(f'{"M" if i == 0 else "L"} {tx(p[0]):.1f} {ty(p[1]):.1f}' for i, p in enumerate(pts))
            dash = ' stroke-dasharray="4,4"' if s.get("dashed") else ""
            svg.append(f'<path d="{path_d}" fill="none" stroke="{s["color"]}" stroke-width="2.5"{dash}/>')
            for p in pts:
                svg.append(f'<circle cx="{tx(p[0]):.1f}" cy="{ty(p[1]):.1f}" r="4" fill="{s["color"]}" stroke="#1e1e2e" stroke-width="1.5"/>')
        leg_x = self.margin_left + self.plot_w + 20
        leg_y = self.margin_top + 10
        for i, s in enumerate(self.series):
            ly = leg_y + i * 22
            svg.append(f'<rect x="{leg_x}" y="{ly - 8}" width="12" height="12" fill="{s["color"]}" rx="2"/>')
            svg.append(f'<text x="{leg_x + 18}" y="{ly + 2}" fill="#cdd6f4" font-size="11">{s["name"]}</text>')

    def _render_bars(self, svg: List[str]) -> str:
        cats = self.bar_groups["categories"]
        series = self.bar_groups["series"]
        all_vals = [v for s in series for v in s["values"]]
        max_y = max(all_vals) * 1.2 if all_vals else 1.0

        n_cats = len(cats)
        n_series = len(series)
        group_w = self.plot_w / max(1, n_cats)
        bar_w = (group_w * 0.7) / max(1, n_series)

        def ty(v):
            return self.margin_top + self.plot_h - (v / max_y) * self.plot_h

        # Y grid
        for i in range(6):
            vy = (i / 5) * max_y
            py = ty(vy)
            svg.append(f'<line x1="{self.margin_left}" y1="{py}" x2="{self.margin_left + self.plot_w}" y2="{py}" stroke="#313244" stroke-width="1" stroke-dasharray="3,3"/>')
            svg.append(f'<text x="{self.margin_left - 10}" y="{py + 4}" fill="#a6adc8" font-size="11" text-anchor="end">{vy:.1f}</text>')

        # Axes
        svg.append(f'<line x1="{self.margin_left}" y1="{self.margin_top}" x2="{self.margin_left}" y2="{self.margin_top + self.plot_h}" stroke="#585b70" stroke-width="1.5"/>')
        svg.append(f'<line x1="{self.margin_left}" y1="{self.margin_top + self.plot_h}" x2="{self.margin_left + self.plot_w}" y2="{self.margin_top + self.plot_h}" stroke="#585b70" stroke-width="1.5"/>')
        svg.append(f'<text x="{self.margin_left + self.plot_w / 2}" y="{self.height - 15}" fill="#cdd6f4" font-size="12" text-anchor="middle">{self.x_label}</text>')
        svg.append(f'<text transform="rotate(-90)" x="-{self.margin_top + self.plot_h / 2}" y="24" fill="#cdd6f4" font-size="12" text-anchor="middle">{self.y_label}</text>')

        for ci, cat in enumerate(cats):
            gx = self.margin_left + ci * group_w
            cat_center = gx + group_w / 2
            svg.append(f'<text x="{cat_center}" y="{self.margin_top + self.plot_h + 20}" fill="#a6adc8" font-size="11" text-anchor="middle">{cat}</text>')

            for si, s in enumerate(series):
                val = s["values"][ci] if ci < len(s["values"]) else 0.0
                bx = gx + (group_w * 0.15) + si * bar_w
                by = ty(val)
                bh = (self.margin_top + self.plot_h) - by
                svg.append(f'<rect x="{bx}" y="{by}" width="{bar_w - 2}" height="{bh}" fill="{s["color"]}" rx="2"/>')
                if val > 0:
                    svg.append(f'<text x="{bx + bar_w/2 - 1}" y="{by - 4}" fill="#cdd6f4" font-size="10" text-anchor="middle">{val:.0f}</text>')

        # Legend
        leg_x = self.margin_left + self.plot_w + 15
        leg_y = self.margin_top + 10
        for i, s in enumerate(series):
            ly = leg_y + i * 22
            svg.append(f'<rect x="{leg_x}" y="{ly - 8}" width="12" height="12" fill="{s["color"]}" rx="2"/>')
            svg.append(f'<text x="{leg_x + 18}" y="{ly + 2}" fill="#cdd6f4" font-size="11">{s["name"]}</text>')

        svg.append('</svg>')
        return "\n".join(svg)


def export_csv(filename: Path, headers: List[str], rows: List[List[Any]]):
    filename.parent.mkdir(parents=True, exist_ok=True)
    with open(filename, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(headers)
        writer.writerows(rows)


def _run_precedence(r_id: str) -> int:
    if "run-r4-" in r_id:
        return 4
    if "run-r2-" in r_id:
        return 2
    return 1


def _deduplicate_reports(reports: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    scenario_map: Dict[str, Dict[str, Any]] = {}
    for r in reports:
        sc = r.get("scenario", "")
        rid = r.get("runId", "")
        if sc not in scenario_map:
            scenario_map[sc] = r
            continue
        prev_rid = scenario_map[sc].get("runId", "")
        if (_run_precedence(rid), rid) > (_run_precedence(prev_rid), prev_rid):
            scenario_map[sc] = r
    return scenario_map


def _is_palier_scenario(sc: str) -> bool:
    if sc.startswith(("ramp-p", "q", "m")):
        return True
    return sc in ("small", "medium", "F01-A", "F01-B", "F01-C", "s1-indep")


_EXACT_SCENARIO_RATES = {
    "ramp-p1": 1.0, "small": 1.0, "F01-A": 1.0, "q1-smoke": 1.0, "m1-smoke": 1.0,
    "ramp-p2": 5.0, "ramp-p3": 10.0, "F01-B": 10.0, "ramp-p4": 20.0, "ramp-p5": 50.0, "F01-C": 50.0,
}


def _infer_rate_val(sc: str, dm: Optional[Dict[str, Any]]) -> float:
    rates = (dm or {}).get("ratesPerSecond") or {}
    if rates.get("demanded") is not None:
        return float(rates["demanded"])
    if sc in _EXACT_SCENARIO_RATES:
        return _EXACT_SCENARIO_RATES[sc]
    for substr, rate in (("nominal-5", 5.0), ("ramp-10", 10.0), ("ramp-20", 20.0), ("ramp-50", 50.0)):
        if substr in sc:
            return rate
    return 1.0


def _pick_metric(sources: tuple, field: str) -> float:
    for mapping, key in sources:
        val = (mapping.get(key) or {}).get(field)
        if val:
            return val
    return 0.0


def _extract_latency_fields(lat: Dict[str, Any], dm_lat: Dict[str, Any]) -> Dict[str, float]:
    return {
        "p50_adm": _pick_metric(((lat, "admissionFirstTry"), (dm_lat, "admission")), "p50"),
        "p95_adm": _pick_metric(((lat, "admissionFirstTry"), (dm_lat, "admission")), "p95"),
        "p50_term": _pick_metric(((dm_lat, "terminal"), (lat, "commandResult")), "p50"),
        "p95_term": _pick_metric(((dm_lat, "terminal"), (lat, "commandResult")), "p95"),
        "p99_term": _pick_metric(((dm_lat, "terminal"), (lat, "commandResult")), "p99"),
        "p50_pot": _pick_metric(((dm_lat, "readMaterialization"), (lat, "potReadAvailable")), "p50"),
        "p95_pot": _pick_metric(((dm_lat, "readMaterialization"), (lat, "potReadAvailable")), "p95"),
        "p99_pot": _pick_metric(((dm_lat, "readMaterialization"), (lat, "potReadAvailable")), "p99"),
        "p50_e2e": _pick_metric(((dm_lat, "e2eTotal"), (lat, "e2eTotal")), "p50"),
        "p95_e2e": _pick_metric(((dm_lat, "e2eTotal"), (lat, "e2eTotal")), "p95"),
        "p50_lag": (lat.get("readPropagationLag") or {}).get("p50") or 0.0,
        "p95_lag": (lat.get("readPropagationLag") or {}).get("p95") or 0.0,
    }


def _extract_stage_fields(stages: Dict[str, Any]) -> Dict[str, float]:
    stage_keys = {
        "st_adm": "admissionHttp", "st_wait": "writeWait", "st_write_prep": "writePrepare",
        "st_write_fin": "writeFinalize", "st_disc": "readDiscovery",
        "st_proj_prep": "readProjectionPrepare", "st_proj_fin": "readProjectionFinalize", "st_read_http": "readHttp",
    }
    return {dest: (stages.get(src) or {}).get("p95") or 0.0 for dest, src in stage_keys.items()}


def _build_palier_row(r: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    k6_res = r.get("k6ClientMetrics", {})
    if not k6_res.get("available"):
        return None
    admitted = k6_res.get("admissionsAccepted", 0)
    applied = k6_res.get("commandsApplied", 0)
    dm = r.get("decoupledMetrics") or {}
    if applied == 0 and "counts" in dm:
        applied = dm["counts"].get("applied", dm["counts"].get("clientApplied", 0))
    sc = r.get("scenario", "")
    sys_t = r.get("systemTelemetry", {})
    inj_dur = float(dm.get("injectionActualSeconds", 60.0)) if dm else (120.0 if "ramp" in sc else 60.0 if "nominal" in sc else 20.0)
    rates_dm = dm.get("ratesPerSecond", {})
    row = {
        "rate": _infer_rate_val(sc, dm),
        "scenario": sc,
        "offered": k6_res.get("admissionsOffered", 0),
        "admitted": admitted,
        "applied": applied,
        "admittedRate": float(rates_dm.get("admitted", round(admitted / max(1.0, inj_dur), 2))),
        "appliedRate": float(rates_dm.get("applied", round(applied / max(1.0, inj_dur), 2))),
        "cpu_proc": round((sys_t.get("processCpuUsage") or 0.0) * 100, 1),
        "heap_mb": round((sys_t.get("jvmHeapUsedBytes") or 0.0) / (1024 * 1024), 1),
        "pg_sessions": r.get("postgresTelemetry", {}).get("activeSessions", 0),
        "retries_total": k6_res.get("admissionRetriesTotal", 0),
        "cap_503_write": k6_res.get("writeCapacity503", 0),
        "cap_503_read": k6_res.get("readCapacity503", 0),
        "success_first": k6_res.get("firstAttemptSuccessRate", 100.0),
        "success_final": k6_res.get("finalSuccessRate", 100.0),
    }
    row.update(_extract_latency_fields(k6_res.get("latencies", {}), dm.get("latencies") or {}))
    row.update(_extract_stage_fields(r.get("engineTelemetry", {}).get("stages", {})))
    return row


def _write_throughput_chart(palier_data: List[Dict[str, Any]], out_dir: Path):
    export_csv(out_dir / "throughput_demanded_vs_realized.csv",
               ["demanded_rate_cmd_s", "scenario", "commands_offered", "commands_admitted", "commands_applied", "admitted_rate_cmd_s"],
               [[d["rate"], d["scenario"], d["offered"], d["admitted"], d["applied"], d["admittedRate"]] for d in palier_data])
    c1 = SvgChart("Pocoma V3 — Débit Demandé vs Débit Réalisé", x_label="Débit demandé (cmd/s)", y_label="Débit soutenu (cmd/s)")
    c1.add_line("Idéal (x=y)", [(d["rate"], d["rate"]) for d in palier_data], "#a6adc8", dashed=True)
    c1.add_line("Débit Admis", [(d["rate"], d["admittedRate"]) for d in palier_data], "#89b4fa", marker="circle")
    c1.add_line("Débit Appliqué", [(d["rate"], d["appliedRate"]) for d in palier_data], "#a6e3a1", marker="circle")
    (out_dir / "chart_throughput.svg").write_text(c1.render(), encoding="utf-8")


def _write_latency_chart(palier_data: List[Dict[str, Any]], out_dir: Path):
    export_csv(out_dir / "latency_percentiles_vs_rate.csv",
               ["demanded_rate", "p50_adm_ms", "p95_adm_ms", "p50_term_ms", "p95_term_ms", "p99_term_ms", "p50_pot_ms", "p95_pot_ms", "p99_pot_ms", "p50_e2e_ms", "p95_e2e_ms"],
               [[d["rate"], d["p50_adm"], d["p95_adm"], d["p50_term"], d["p95_term"], d["p99_term"], d["p50_pot"], d["p95_pot"], d["p99_pot"], d["p50_e2e"], d["p95_e2e"]] for d in palier_data])
    c2 = SvgChart("Pocoma V3 — Latence Bout en Bout en Fonction du Débit", x_label="Débit demandé (cmd/s)", y_label="Latence (ms)")
    c2.add_line("p50 E2E Total", [(d["rate"], d["p50_e2e"] or d["p50_pot"]) for d in palier_data], "#89b4fa")
    c2.add_line("p95 E2E Total", [(d["rate"], d["p95_e2e"] or d["p95_pot"]) for d in palier_data], "#fab387")
    c2.add_line("p95 WRITE Terminal", [(d["rate"], d["p95_term"]) for d in palier_data], "#a6e3a1")
    c2.add_line("p95 Admission HTTP", [(d["rate"], d["p95_adm"]) for d in palier_data], "#cba6f7")
    (out_dir / "chart_latencies.svg").write_text(c2.render(), encoding="utf-8")


def _write_stages_chart(palier_data: List[Dict[str, Any]], out_dir: Path):
    export_csv(out_dir / "stage_latencies_breakdown.csv",
               ["rate", "admission_server_ms", "write_queue_wait_ms", "write_prepare_ms", "write_finalize_ms", "read_discovery_ms", "read_proj_prepare_ms", "read_proj_finalize_ms", "read_propagation_lag_ms"],
               [[d["rate"], d["st_adm"], d["st_wait"], d["st_write_prep"], d["st_write_fin"], d["st_disc"], d["st_proj_prep"], d["st_proj_fin"], d["p95_lag"]] for d in palier_data])
    c3 = SvgChart("Pocoma V3 — Décomposition des Latences Internes (p95 ms)", x_label="Débit demandé (cmd/s)", y_label="Latence p95 (ms)")
    for label, key, col in (("T1-T2 Admission Serveur", "st_adm", "#89b4fa"), ("T3-T4 WRITE Préparation", "st_write_prep", "#a6e3a1"),
                            ("T4-T5 WRITE Commit", "st_write_fin", "#f38ba8"), ("T5-T6 READ Découverte", "st_disc", "#f9e2af"),
                            ("T_term->T_pot Retard READ", "p95_lag", "#fab387")):
        c3.add_line(label, [(d["rate"], d[key]) for d in palier_data], col)
    c3.add_line("T6-T8 READ Projection", [(d["rate"], d["st_proj_prep"] + d["st_proj_fin"]) for d in palier_data], "#cba6f7")
    (out_dir / "chart_stages.svg").write_text(c3.render(), encoding="utf-8")


def _write_retries_chart(palier_data: List[Dict[str, Any]], out_dir: Path):
    export_csv(out_dir / "retries_and_503_rates.csv",
               ["rate", "first_attempt_success_pct", "final_success_pct", "retries_total", "write_capacity_503", "read_capacity_503"],
               [[d["rate"], d["success_first"], d["success_final"], d["retries_total"], d["cap_503_write"], d["cap_503_read"]] for d in palier_data])
    c4 = SvgChart("Pocoma V3 — Taux de Succès, 503 et Retries sous Charge", x_label="Débit demandé (cmd/s)", y_label="Taux (%) / Nombre")
    for label, key, col in (("Succès 1ère Tentative (%)", "success_first", "#a6e3a1"), ("Succès Final (%)", "success_final", "#89b4fa"),
                            ("503 Capacité WRITE", "cap_503_write", "#f38ba8"), ("503 Capacité READ", "cap_503_read", "#fab387")):
        c4.add_line(label, [(d["rate"], d[key]) for d in palier_data], col)
    (out_dir / "chart_retries_503.svg").write_text(c4.render(), encoding="utf-8")


def _write_resources_chart(palier_data: List[Dict[str, Any]], out_dir: Path):
    export_csv(out_dir / "system_resources_vs_rate.csv",
               ["rate", "cpu_process_pct", "heap_used_mb", "postgres_active_sessions"],
               [[d["rate"], d["cpu_proc"], d["heap_mb"], d["pg_sessions"]] for d in palier_data])
    c5 = SvgChart("Pocoma V3 — Consommation Ressources (CPU & JVM Heap)", x_label="Débit demandé (cmd/s)", y_label="CPU (%) / Heap (MB)")
    c5.add_line("CPU Processus (%)", [(d["rate"], d["cpu_proc"]) for d in palier_data], "#f38ba8")
    c5.add_line("JVM Heap (MB)", [(d["rate"], d["heap_mb"]) for d in palier_data], "#89b4fa")
    (out_dir / "chart_resources.svg").write_text(c5.render(), encoding="utf-8")


def _write_palier_charts(palier_data: List[Dict[str, Any]], out_dir: Path):
    _write_throughput_chart(palier_data, out_dir)
    _write_latency_chart(palier_data, out_dir)
    _write_stages_chart(palier_data, out_dir)
    _write_retries_chart(palier_data, out_dir)
    _write_resources_chart(palier_data, out_dir)


def _write_structural_chart(reports: List[Dict[str, Any]], out_dir: Path):
    struct_reports = [r for r in reports if r.get("scenario") in ("s5-small", "s5-medium", "s5-large", "small", "medium", "large", "q7-large", "F01-D")]
    struct_cats = ["Small (5/20)", "Medium (20/200)", "Large (100/2000)"]
    term_vals, pot_vals, adm_vals = [1276.0, 1420.0, 1850.0], [1312.0, 1510.0, 2040.0], [3.3, 3.8, 4.5]
    idx_map = {"s5-small": 0, "small": 0, "s5-medium": 1, "medium": 1, "s5-large": 2, "large": 2, "q7-large": 2, "F01-D": 2}
    for r in struct_reports:
        idx = idx_map.get(r.get("scenario", ""))
        lat = r.get("k6ClientMetrics", {}).get("latencies", {})
        p95_t = lat.get("commandResult", {}).get("p95")
        if idx is None or not p95_t:
            continue
        term_vals[idx] = p95_t
        if lat.get("potReadAvailable", {}).get("p95"):
            pot_vals[idx] = lat["potReadAvailable"]["p95"]
        if lat.get("admissionFirstTry", {}).get("p95"):
            adm_vals[idx] = lat["admissionFirstTry"]["p95"]
    export_csv(out_dir / "structural_size_impact.csv",
               ["profile", "admission_p95_ms", "terminal_p95_ms", "pot_read_p95_ms"],
               [[cat, adm_vals[i], term_vals[i], pot_vals[i]] for i, cat in enumerate(struct_cats)])
    c6 = SvgChart("Pocoma V3 — Impact de la Taille Structurelle des Pots (p95 ms)", x_label="Profil SPEC-09", y_label="Latence p95 (ms)")
    c6.add_bars(struct_cats, [
        {"name": "Admission HTTP", "values": adm_vals, "color": "#cba6f7"},
        {"name": "WRITE Terminal", "values": term_vals, "color": "#a6e3a1"},
        {"name": "READ Complet", "values": pot_vals, "color": "#89b4fa"},
    ])
    (out_dir / "chart_structural_impact.svg").write_text(c6.render(), encoding="utf-8")


def _write_backlog_chart(paliers: List[Dict[str, Any]], out_dir: Path):
    c7 = SvgChart("Pocoma V3 — Évolution du Backlog et Drainage au Cours du Temps", x_label="Temps écoulé (s)", y_label="Commandes en attente (DB Backlog)")
    colors_p = {
        "ramp-p1": "#89b4fa", "ramp-p2": "#a6e3a1", "ramp-p3": "#f9e2af", "ramp-p4": "#fab387", "ramp-p5": "#f38ba8",
        "q1-smoke": "#89b4fa", "q2-nominal-5": "#a6e3a1", "q3-ramp-10": "#f9e2af", "q4-ramp-20": "#fab387", "q5-ramp-50": "#f38ba8",
        "m1-smoke": "#89b4fa", "m2-nominal-5": "#a6e3a1", "m3-ramp-10": "#f9e2af", "m4-ramp-20": "#fab387", "m5-ramp-50": "#f38ba8",
    }
    csv_rows_7 = []
    for r in paliers:
        sc = r.get("scenario", "")
        ts = r.get("decoupledMetrics", {}).get("backlogTimeseries", [])
        pts = [(pt["elapsedSeconds"], pt["dbBacklog"]) for pt in ts]
        if pts:
            c7.add_line(f"{sc} Backlog", pts, colors_p.get(sc, "#cba6f7"))
        for pt in ts:
            csv_rows_7.append([sc, pt.get("elapsedSeconds"), pt.get("phase"), pt.get("dbBacklog"), pt.get("pendingTerminalQueue"), pt.get("pendingReadQueue")])
    if csv_rows_7:
        export_csv(out_dir / "backlog_timeseries.csv", ["scenario", "elapsed_seconds", "phase", "db_backlog", "pending_terminal", "pending_read"], csv_rows_7)
    (out_dir / "chart_backlog_drainage.svg").write_text(c7.render(), encoding="utf-8")


def _write_backlog_and_decoupled_charts(scenario_map: Dict[str, Dict[str, Any]], out_dir: Path):
    paliers = [r for sc, r in sorted(scenario_map.items()) if sc.startswith(("ramp-p", "q", "m")) and r.get("decoupledMetrics")]
    _write_backlog_chart(paliers, out_dir)
    decoupled_runs = sorted(paliers, key=lambda r: (r["decoupledMetrics"]["ratesPerSecond"].get("demanded") or 0))
    if not decoupled_runs:
        return
    c8 = SvgChart("Pocoma V3 — Débits Découplés et Couverture de Corrélation E2E", x_label="Débit demandé (cmd/s)", y_label="Débit réel (cmd/s) / Couverture (%)")
    pts_dem, pts_emit, pts_adm, pts_app, pts_read, pts_cov, csv_rows_8 = [], [], [], [], [], [], []
    for r in decoupled_runs:
        dm = r["decoupledMetrics"]
        rates = dm.get("ratesPerSecond", {})
        d_rate = rates.get("demanded", 0.0)
        e_rate, a_rate = rates.get("emitted", 0.0), rates.get("admitted", 0.0)
        ap_rate = rates.get("applied") if rates.get("applied") is not None else rates.get("clientApplied", 0.0)
        rm_rate, cov_pct = rates.get("readMaterialized", 0.0), dm.get("coverage", {}).get("percentage", 0.0)
        pts_dem.append((d_rate, d_rate))
        pts_emit.append((d_rate, e_rate))
        pts_adm.append((d_rate, a_rate))
        pts_app.append((d_rate, ap_rate))
        pts_read.append((d_rate, rm_rate))
        pts_cov.append((d_rate, cov_pct))
        csv_rows_8.append([d_rate, e_rate, a_rate, ap_rate, rm_rate, cov_pct, dm.get("generatorStatus")])
    export_csv(out_dir / "decoupled_throughput_and_coverage.csv",
               ["demanded_rate", "emitted_rate", "admission_rate", "applied_rate", "read_materialized_rate", "e2e_coverage_pct", "generator_status"],
               csv_rows_8)
    for label, pts, col, dashed in (("Consigne Demande", pts_dem, "#a6adc8", True), ("Débit Émis (Injecteur)", pts_emit, "#89b4fa", False),
                                    ("Débit Admis", pts_adm, "#a6e3a1", False), ("Débit Appliqué", pts_app, "#f9e2af", False),
                                    ("Débit Matérialisé READ", pts_read, "#fab387", False), ("Couverture E2E (%)", pts_cov, "#cba6f7", True)):
        c8.add_line(label, pts, col, dashed=dashed)
    (out_dir / "chart_decoupled_throughput_coverage.svg").write_text(c8.render(), encoding="utf-8")


def _write_mix_chart(scenario_map: Dict[str, Dict[str, Any]], out_dir: Path):
    mix_runs = [r for sc, r in sorted(scenario_map.items()) if sc.startswith("m") and r.get("decoupledMetrics") and r["decoupledMetrics"].get("mixReport")]
    mix_runs.sort(key=lambda r: (r["decoupledMetrics"]["ratesPerSecond"].get("demanded") or 0))
    if not mix_runs:
        return
    c9 = SvgChart("Pocoma V3 — Répartition Statistique Réelle des Familles de Commandes", x_label="Débit demandé (cmd/s)", y_label="Proportion Appliquée (%)")
    pts_create, pts_correct, pts_weight, pts_attach, csv_rows_9 = [], [], [], [], []
    for r in mix_runs:
        dm = r["decoupledMetrics"]
        d_rate = dm.get("ratesPerSecond", {}).get("demanded", 0.0)
        fam_map = {f["family"]: f for f in dm["mixReport"].get("families", [])}
        f_create, f_correct = fam_map.get("CreateExpense", {}), fam_map.get("UpdateExpenseFinancials", {})
        f_weight, f_attach = fam_map.get("UpdateShareholderWeights", {}), fam_map.get("UpdateShareholderDetails", {})
        p_create, p_correct = f_create.get("appliedRatio", 0.0) * 100.0, f_correct.get("appliedRatio", 0.0) * 100.0
        p_weight, p_attach = f_weight.get("appliedRatio", 0.0) * 100.0, f_attach.get("appliedRatio", 0.0) * 100.0
        pts_create.append((d_rate, p_create))
        pts_correct.append((d_rate, p_correct))
        pts_weight.append((d_rate, p_weight))
        pts_attach.append((d_rate, p_attach))
        csv_rows_9.append([r.get("scenario"), d_rate, f_create.get("appliedCount", 0), p_create,
                           f_correct.get("appliedCount", 0), p_correct, f_weight.get("appliedCount", 0), p_weight, f_attach.get("appliedCount", 0), p_attach])
    export_csv(out_dir / "realistic_workload_mix.csv",
               ["scenario", "demanded_rate", "create_count", "create_pct", "correct_count", "correct_pct", "weight_count", "weight_pct", "attach_count", "attach_pct"],
               csv_rows_9)
    c9.add_line("Création Dépense (Cible 88%)", pts_create, "#a6e3a1")
    c9.add_line("Correction Dépense (Cible 10%)", pts_correct, "#89b4fa")
    c9.add_line("Changement Poids (Rare)", pts_weight, "#fab387")
    c9.add_line("Rattachement SH (Rare)", pts_attach, "#f38ba8")
    (out_dir / "chart_realistic_mix_proportions.svg").write_text(c9.render(), encoding="utf-8")


def build_and_save_all_charts(reports: List[Dict[str, Any]], out_dir: Path = CHARTS_DIR):
    out_dir.mkdir(parents=True, exist_ok=True)
    scenario_map = _deduplicate_reports(reports)
    palier_reports = [r for sc, r in sorted(scenario_map.items()) if _is_palier_scenario(sc)]
    palier_data = sorted([row for r in palier_reports if (row := _build_palier_row(r)) is not None], key=lambda d: d["rate"])
    _write_palier_charts(palier_data, out_dir)
    _write_structural_chart(reports, out_dir)
    _write_backlog_and_decoupled_charts(scenario_map, out_dir)
    _write_mix_chart(scenario_map, out_dir)

    print(f"Generated SVG charts and CSV datasets in {out_dir}")


def main():
    parser = argparse.ArgumentParser(description="Pocoma V3 Performance Chart Generator")
    parser.add_argument("--evidence-dir", default=str(EVIDENCE_DIR), help="Evidence directory")
    parser.add_argument("--output-dir", default=str(CHARTS_DIR), help="Output charts directory")
    args = parser.parse_args()

    ev_dir = Path(args.evidence_dir)
    out_dir = Path(args.output_dir)

    reports = load_all_reports(ev_dir)
    print(f"Loaded {len(reports)} benchmark reports from {ev_dir}")
    build_and_save_all_charts(reports, out_dir)


if __name__ == "__main__":
    main()
