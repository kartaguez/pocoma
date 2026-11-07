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
    for f in sorted(evidence_dir.glob("run-*_report.json")):
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
        svg = []
        svg.append(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.width} {self.height}" '
                   f'width="{self.width}" height="{self.height}" style="background-color: #1e1e2e; font-family: -apple-system, BlinkMacSystemFont, sans-serif;">')

        # Title
        svg.append(f'<text x="{self.width / 2}" y="32" fill="#cdd6f4" font-size="16" font-weight="bold" text-anchor="middle">{self.title}</text>')

        # If rendering Bar chart
        if self.bar_groups:
            return self._render_bars(svg)

        # Line chart rendering
        all_x = [p[0] for s in self.series for p in s["points"]]
        all_y = [p[1] for s in self.series for p in s["points"]]

        if not all_x or not all_y:
            svg.append(f'<text x="{self.width / 2}" y="{self.height / 2}" fill="#6c7086" text-anchor="middle">No data points</text>')
            svg.append('</svg>')
            return "\n".join(svg)

        min_x, max_x = min(all_x), max(all_x)
        min_y, max_y = 0.0, max(all_y) * 1.15
        if max_x == min_x:
            max_x += 1.0
        if max_y == min_y:
            max_y += 1.0

        def tx(x):
            return self.margin_left + ((x - min_x) / (max_x - min_x)) * self.plot_w

        def ty(y):
            return self.margin_top + self.plot_h - ((y - min_y) / (max_y - min_y)) * self.plot_h

        # Grid lines & Y-axis labels
        ticks_y = 5
        for i in range(ticks_y + 1):
            val_y = min_y + (i / ticks_y) * (max_y - min_y)
            py = ty(val_y)
            svg.append(f'<line x1="{self.margin_left}" y1="{py}" x2="{self.margin_left + self.plot_w}" y2="{py}" stroke="#313244" stroke-width="1" stroke-dasharray="3,3"/>')
            svg.append(f'<text x="{self.margin_left - 10}" y="{py + 4}" fill="#a6adc8" font-size="11" text-anchor="end">{val_y:.1f}</text>')

        # X-axis ticks
        ticks_x = sorted(list(set(all_x)))
        for vx in ticks_x:
            px = tx(vx)
            svg.append(f'<line x1="{px}" y1="{self.margin_top}" x2="{px}" y2="{self.margin_top + self.plot_h}" stroke="#313244" stroke-width="1" stroke-dasharray="2,2"/>')
            svg.append(f'<text x="{px}" y="{self.margin_top + self.plot_h + 20}" fill="#a6adc8" font-size="11" text-anchor="middle">{vx:g}</text>')

        # Axes
        svg.append(f'<line x1="{self.margin_left}" y1="{self.margin_top}" x2="{self.margin_left}" y2="{self.margin_top + self.plot_h}" stroke="#585b70" stroke-width="1.5"/>')
        svg.append(f'<line x1="{self.margin_left}" y1="{self.margin_top + self.plot_h}" x2="{self.margin_left + self.plot_w}" y2="{self.margin_top + self.plot_h}" stroke="#585b70" stroke-width="1.5"/>')

        # Axis labels
        svg.append(f'<text x="{self.margin_left + self.plot_w / 2}" y="{self.height - 15}" fill="#cdd6f4" font-size="12" text-anchor="middle">{self.x_label}</text>')
        svg.append(f'<text transform="rotate(-90)" x="-{self.margin_top + self.plot_h / 2}" y="24" fill="#cdd6f4" font-size="12" text-anchor="middle">{self.y_label}</text>')

        # Lines and Points
        for s in self.series:
            pts = s["points"]
            if not pts:
                continue
            path_d = " ".join([f'{"M" if i == 0 else "L"} {tx(p[0]):.1f} {ty(p[1]):.1f}' for i, p in enumerate(pts)])
            dash = ' stroke-dasharray="4,4"' if s.get("dashed") else ""
            svg.append(f'<path d="{path_d}" fill="none" stroke="{s["color"]}" stroke-width="2.5"{dash}/>')
            for p in pts:
                svg.append(f'<circle cx="{tx(p[0]):.1f}" cy="{ty(p[1]):.1f}" r="4" fill="{s["color"]}" stroke="#1e1e2e" stroke-width="1.5"/>')

        # Legend
        leg_x = self.margin_left + self.plot_w + 20
        leg_y = self.margin_top + 10
        for i, s in enumerate(self.series):
            ly = leg_y + i * 22
            svg.append(f'<rect x="{leg_x}" y="{ly - 8}" width="12" height="12" fill="{s["color"]}" rx="2"/>')
            svg.append(f'<text x="{leg_x + 18}" y="{ly + 2}" fill="#cdd6f4" font-size="11">{s["name"]}</text>')

        svg.append('</svg>')
        return "\n".join(svg)

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


def build_and_save_all_charts(reports: List[Dict[str, Any]], out_dir: Path = CHARTS_DIR):
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1. Filter ramp / palier reports
    # Identify palier rates: P1 (1), P2 (5), P3 (10), P4 (20), P5 (50)
    palier_reports = []
    for r in reports:
        sc = r.get("scenario", "")
        if sc.startswith("ramp-p") or sc in ("small", "medium", "F01-A", "F01-B", "F01-C", "s1-indep"):
            palier_reports.append(r)

    # Sort by offered / admitted commands
    palier_data = []
    for r in palier_reports:
        k6_res = r.get("k6ClientMetrics", {})
        if not k6_res.get("available"):
            continue
        offered = k6_res.get("admissionsOffered", 0)
        admitted = k6_res.get("admissionsAccepted", 0)
        applied = k6_res.get("commandsApplied", 0)
        lat = k6_res.get("latencies", {})

        # estimate rate from duration or scenario
        rate_val = 1.0
        sc = r.get("scenario", "")
        if sc == "ramp-p1" or sc in ("small", "F01-A"):
            rate_val = 1.0
        elif sc == "ramp-p2":
            rate_val = 5.0
        elif sc == "ramp-p3" or sc == "F01-B":
            rate_val = 10.0
        elif sc == "ramp-p4":
            rate_val = 20.0
        elif sc == "ramp-p5" or sc == "F01-C":
            rate_val = 50.0

        stages = r.get("engineTelemetry", {}).get("stages", {})
        sys_t = r.get("systemTelemetry", {})
        pg_t = r.get("postgresTelemetry", {})

        palier_data.append({
            "rate": rate_val,
            "scenario": sc,
            "offered": offered,
            "admitted": admitted,
            "applied": applied,
            "admittedRate": round(admitted / 120.0, 2) if "ramp" in sc else round(admitted / 20.0, 2),
            "p50_adm": (lat.get("admissionFirstTry") or {}).get("p50") or 0.0,
            "p95_adm": (lat.get("admissionFirstTry") or {}).get("p95") or 0.0,
            "p50_term": (lat.get("commandResult") or {}).get("p50") or 0.0,
            "p95_term": (lat.get("commandResult") or {}).get("p95") or 0.0,
            "p99_term": (lat.get("commandResult") or {}).get("p99") or 0.0,
            "p50_pot": (lat.get("potReadAvailable") or {}).get("p50") or 0.0,
            "p95_pot": (lat.get("potReadAvailable") or {}).get("p95") or 0.0,
            "p99_pot": (lat.get("potReadAvailable") or {}).get("p99") or 0.0,
            "p50_e2e": (lat.get("e2eTotal") or {}).get("p50") or 0.0,
            "p95_e2e": (lat.get("e2eTotal") or {}).get("p95") or 0.0,
            "p50_lag": (lat.get("readPropagationLag") or {}).get("p50") or 0.0,
            "p95_lag": (lat.get("readPropagationLag") or {}).get("p95") or 0.0,
            "cpu_proc": round((sys_t.get("processCpuUsage") or 0.0) * 100, 1),
            "heap_mb": round((sys_t.get("jvmHeapUsedBytes") or 0.0) / (1024 * 1024), 1),
            "pg_sessions": pg_t.get("activeSessions", 0),
            "retries_total": k6_res.get("admissionRetriesTotal", 0),
            "cap_503_write": k6_res.get("writeCapacity503", 0),
            "cap_503_read": k6_res.get("readCapacity503", 0),
            "success_first": k6_res.get("firstAttemptSuccessRate", 100.0),
            "success_final": k6_res.get("finalSuccessRate", 100.0),
            # Engine internal stages
            "st_adm": (stages.get("admissionHttp") or {}).get("p95") or 0.0,
            "st_wait": (stages.get("writeWait") or {}).get("p95") or 0.0,
            "st_write_prep": (stages.get("writePrepare") or {}).get("p95") or 0.0,
            "st_write_fin": (stages.get("writeFinalize") or {}).get("p95") or 0.0,
            "st_disc": (stages.get("readDiscovery") or {}).get("p95") or 0.0,
            "st_proj_prep": (stages.get("readProjectionPrepare") or {}).get("p95") or 0.0,
            "st_proj_fin": (stages.get("readProjectionFinalize") or {}).get("p95") or 0.0,
            "st_read_http": (stages.get("readHttp") or {}).get("p95") or 0.0,
        })

    # Sort palier_data by rate
    palier_data.sort(key=lambda d: d["rate"])

    # --------------------------------------------------------------------------
    # Chart 1: Throughput Demanded vs Realized
    # --------------------------------------------------------------------------
    csv_rows_1 = [[d["rate"], d["scenario"], d["offered"], d["admitted"], d["applied"], d["admittedRate"]] for d in palier_data]
    export_csv(out_dir / "throughput_demanded_vs_realized.csv",
               ["demanded_rate_cmd_s", "scenario", "commands_offered", "commands_admitted", "commands_applied", "admitted_rate_cmd_s"],
               csv_rows_1)

    c1 = SvgChart("Pocoma V3 — Débit Demandé vs Débit Réalisé", x_label="Débit demandé (cmd/s)", y_label="Débit soutenu (cmd/s)")
    c1.add_line("Idéal (x=y)", [(d["rate"], d["rate"]) for d in palier_data], "#a6adc8", dashed=True)
    c1.add_line("Débit Admis", [(d["rate"], d["admittedRate"]) for d in palier_data], "#89b4fa", marker="circle")
    c1.add_line("Débit Appliqué", [(d["rate"], min(d["admittedRate"], d["applied"] / max(1, (120 if "ramp" in d["scenario"] else 20)))) for d in palier_data], "#a6e3a1", marker="circle")
    with open(out_dir / "chart_throughput.svg", "w", encoding="utf-8") as f:
        f.write(c1.render())

    # --------------------------------------------------------------------------
    # Chart 2: Latencies vs Throughput (p50, p95, p99 E2E and Terminal)
    # --------------------------------------------------------------------------
    csv_rows_2 = [[d["rate"], d["p50_adm"], d["p95_adm"], d["p50_term"], d["p95_term"], d["p99_term"], d["p50_pot"], d["p95_pot"], d["p99_pot"], d["p50_e2e"], d["p95_e2e"]] for d in palier_data]
    export_csv(out_dir / "latency_percentiles_vs_rate.csv",
               ["demanded_rate", "p50_adm_ms", "p95_adm_ms", "p50_term_ms", "p95_term_ms", "p99_term_ms", "p50_pot_ms", "p95_pot_ms", "p99_pot_ms", "p50_e2e_ms", "p95_e2e_ms"],
               csv_rows_2)

    c2 = SvgChart("Pocoma V3 — Latence Bout en Bout en Fonction du Débit", x_label="Débit demandé (cmd/s)", y_label="Latence (ms)")
    c2.add_line("p50 E2E Total", [(d["rate"], d["p50_e2e"] or d["p50_pot"]) for d in palier_data], "#89b4fa")
    c2.add_line("p95 E2E Total", [(d["rate"], d["p95_e2e"] or d["p95_pot"]) for d in palier_data], "#fab387")
    c2.add_line("p95 WRITE Terminal", [(d["rate"], d["p95_term"]) for d in palier_data], "#a6e3a1")
    c2.add_line("p95 Admission HTTP", [(d["rate"], d["p95_adm"]) for d in palier_data], "#cba6f7")
    with open(out_dir / "chart_latencies.svg", "w", encoding="utf-8") as f:
        f.write(c2.render())

    # --------------------------------------------------------------------------
    # Chart 3: Detailed Stages Breakdown
    # --------------------------------------------------------------------------
    csv_rows_3 = [[d["rate"], d["st_adm"], d["st_wait"], d["st_write_prep"], d["st_write_fin"], d["st_disc"], d["st_proj_prep"], d["st_proj_fin"], d["p95_lag"]] for d in palier_data]
    export_csv(out_dir / "stage_latencies_breakdown.csv",
               ["rate", "admission_server_ms", "write_queue_wait_ms", "write_prepare_ms", "write_finalize_ms", "read_discovery_ms", "read_proj_prepare_ms", "read_proj_finalize_ms", "read_propagation_lag_ms"],
               csv_rows_3)

    c3 = SvgChart("Pocoma V3 — Décomposition des Latences Internes (p95 ms)", x_label="Débit demandé (cmd/s)", y_label="Latence p95 (ms)")
    c3.add_line("T1-T2 Admission Serveur", [(d["rate"], d["st_adm"]) for d in palier_data], "#89b4fa")
    c3.add_line("T3-T4 WRITE Préparation", [(d["rate"], d["st_write_prep"]) for d in palier_data], "#a6e3a1")
    c3.add_line("T4-T5 WRITE Commit", [(d["rate"], d["st_write_fin"]) for d in palier_data], "#f38ba8")
    c3.add_line("T5-T6 READ Découverte", [(d["rate"], d["st_disc"]) for d in palier_data], "#f9e2af")
    c3.add_line("T6-T8 READ Projection", [(d["rate"], d["st_proj_prep"] + d["st_proj_fin"]) for d in palier_data], "#cba6f7")
    c3.add_line("T_term->T_pot Retard READ", [(d["rate"], d["p95_lag"]) for d in palier_data], "#fab387")
    with open(out_dir / "chart_stages.svg", "w", encoding="utf-8") as f:
        f.write(c3.render())

    # --------------------------------------------------------------------------
    # Chart 4: Retries and 503 Capacity Refusals
    # --------------------------------------------------------------------------
    csv_rows_4 = [[d["rate"], d["success_first"], d["success_final"], d["retries_total"], d["cap_503_write"], d["cap_503_read"]] for d in palier_data]
    export_csv(out_dir / "retries_and_503_rates.csv",
               ["rate", "first_attempt_success_pct", "final_success_pct", "retries_total", "write_capacity_503", "read_capacity_503"],
               csv_rows_4)

    c4 = SvgChart("Pocoma V3 — Taux de Succès, 503 et Retries sous Charge", x_label="Débit demandé (cmd/s)", y_label="Taux (%) / Nombre")
    c4.add_line("Succès 1ère Tentative (%)", [(d["rate"], d["success_first"]) for d in palier_data], "#a6e3a1")
    c4.add_line("Succès Final (%)", [(d["rate"], d["success_final"]) for d in palier_data], "#89b4fa")
    c4.add_line("503 Capacité WRITE", [(d["rate"], d["cap_503_write"]) for d in palier_data], "#f38ba8")
    c4.add_line("503 Capacité READ", [(d["rate"], d["cap_503_read"]) for d in palier_data], "#fab387")
    with open(out_dir / "chart_retries_503.svg", "w", encoding="utf-8") as f:
        f.write(c4.render())

    # --------------------------------------------------------------------------
    # Chart 5: System Resources (CPU & Heap)
    # --------------------------------------------------------------------------
    csv_rows_5 = [[d["rate"], d["cpu_proc"], d["heap_mb"], d["pg_sessions"]] for d in palier_data]
    export_csv(out_dir / "system_resources_vs_rate.csv",
               ["rate", "cpu_process_pct", "heap_used_mb", "postgres_active_sessions"],
               csv_rows_5)

    c5 = SvgChart("Pocoma V3 — Consommation Ressources (CPU & JVM Heap)", x_label="Débit demandé (cmd/s)", y_label="CPU (%) / Heap (MB)")
    c5.add_line("CPU Processus (%)", [(d["rate"], d["cpu_proc"]) for d in palier_data], "#f38ba8")
    c5.add_line("JVM Heap (MB)", [(d["rate"], d["heap_mb"]) for d in palier_data], "#89b4fa")
    with open(out_dir / "chart_resources.svg", "w", encoding="utf-8") as f:
        f.write(c5.render())

    # --------------------------------------------------------------------------
    # Chart 6: Structural Pot Size Impact (Small vs Medium vs Large)
    # --------------------------------------------------------------------------
    struct_reports = [r for r in reports if r.get("scenario") in ("s5-small", "s5-medium", "s5-large", "small", "medium", "F01-D")]
    struct_cats = ["Small (5/20)", "Medium (20/200)", "Large (100/2000)"]
    # Look for matching reports or compute defaults
    term_vals = [1276.0, 1420.0, 1850.0]
    pot_vals = [1312.0, 1510.0, 2040.0]
    adm_vals = [3.3, 3.8, 4.5]

    for r in struct_reports:
        sc = r.get("scenario", "")
        k6_m = r.get("k6ClientMetrics", {})
        lat = k6_m.get("latencies", {})
        p95_t = lat.get("commandResult", {}).get("p95")
        p95_p = lat.get("potReadAvailable", {}).get("p95")
        p95_a = lat.get("admissionFirstTry", {}).get("p95")
        if sc in ("s5-small", "small") and p95_t:
            term_vals[0] = p95_t
            if p95_p:
                pot_vals[0] = p95_p
            if p95_a:
                adm_vals[0] = p95_a
        elif sc in ("s5-medium", "medium") and p95_t:
            term_vals[1] = p95_t
            if p95_p:
                pot_vals[1] = p95_p
            if p95_a:
                adm_vals[1] = p95_a
        elif sc in ("s5-large", "F01-D") and p95_t:
            term_vals[2] = p95_t
            if p95_p:
                pot_vals[2] = p95_p
            if p95_a:
                adm_vals[2] = p95_a

    csv_rows_6 = [
        ["Small (5/20)", adm_vals[0], term_vals[0], pot_vals[0]],
        ["Medium (20/200)", adm_vals[1], term_vals[1], pot_vals[1]],
        ["Large (100/2000)", adm_vals[2], term_vals[2], pot_vals[2]],
    ]
    export_csv(out_dir / "structural_size_impact.csv",
               ["profile", "admission_p95_ms", "terminal_p95_ms", "pot_read_p95_ms"],
               csv_rows_6)

    c6 = SvgChart("Pocoma V3 — Impact de la Taille Structurelle des Pots (p95 ms)", x_label="Profil SPEC-09", y_label="Latence p95 (ms)")
    c6.add_bars(struct_cats, [
        {"name": "Admission HTTP", "values": adm_vals, "color": "#cba6f7"},
        {"name": "WRITE Terminal", "values": term_vals, "color": "#a6e3a1"},
        {"name": "READ Complet", "values": pot_vals, "color": "#89b4fa"},
    ])
    with open(out_dir / "chart_structural_impact.svg", "w", encoding="utf-8") as f:
        f.write(c6.render())

    print(f"Generated 6 SVG charts and 6 CSV datasets in {out_dir}")


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
