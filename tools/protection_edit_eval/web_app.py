#!/usr/bin/env python3
"""Run the protection-edit evaluation tools through a loopback-only web page."""

from __future__ import annotations

import argparse
import io
import json
import math
import mimetypes
import re
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from PIL import Image, ImageDraw

from evaluate import MAX_MASK_BYTES, create_preview, prepare_image


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
TOOL_ROOT = Path(__file__).resolve().parent
WEB_ROOT = TOOL_ROOT / "web"
OUTPUT_ROOT = Path.home() / "Downloads" / "kkokkkok-edit-eval" / "web-runs"
MAX_UPLOAD_BYTES = 50 * 1024 * 1024
MAX_BODY_BYTES = MAX_UPLOAD_BYTES + 128 * 1024
MASK_MODEL = "gpt-6.1-terra"
IMAGE_MODELS = {
    "gpt-image-2.5-sunburst",
    "gpt-image-2.5-flare",
    "gpt-image-2",
    "gpt-image-1.5",
    "gpt-image-1-mini",
}
QUALITIES = {"low", "medium", "high", "xhigh", "max"}
EXTRA_QUALITY_MODELS = {"gpt-image-2.5-sunburst", "gpt-image-2.5-flare"}
SESSION_ID = re.compile(r"^[a-f0-9]{16}$")
EDIT_ID = re.compile(r"^[a-f0-9]{16}$")
JOBS = ThreadPoolExecutor(max_workers=1, thread_name_prefix="edit-eval")
JOB_LOCK = threading.Lock()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def session_dir(session_id: str) -> Path:
    if not SESSION_ID.fullmatch(session_id):
        raise ValueError("실험 ID 형식이 올바르지 않습니다.")
    return OUTPUT_ROOT / session_id


def read_manifest(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_manifest(directory: Path, manifest: dict) -> None:
    manifest["updatedAt"] = utc_now()
    temporary = directory / "session.json.tmp"
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(directory / "session.json")


def public_manifest(manifest: dict) -> dict:
    """Return persisted metadata without exposing local filesystem paths."""
    return {
        key: value for key, value in manifest.items()
        if key not in {"inputPath", "maskPath", "maskPreviewPath"}
    }


def parse_form(body: bytes, content_type: str) -> tuple[dict[str, str], tuple[str, bytes] | None]:
    envelope = (
        b"MIME-Version: 1.0\r\nContent-Type: "
        + content_type.encode("ascii", errors="strict")
        + b"\r\n\r\n"
        + body
    )
    message = BytesParser(policy=policy.default).parsebytes(envelope)
    if not message.is_multipart():
        raise ValueError("사진 업로드 형식이 올바르지 않습니다.")
    fields: dict[str, str] = {}
    upload = None
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if not name:
            continue
        payload = part.get_payload(decode=True) or b""
        filename = part.get_filename()
        if filename:
            if name == "image":
                upload = (part.get_content_type(), payload)
        else:
            fields[name] = payload.decode(part.get_content_charset() or "utf-8", errors="replace")
    return fields, upload


def validate_image(content_type: str, data: bytes) -> str:
    suffixes = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}
    if content_type not in suffixes:
        raise ValueError("JPG, PNG, WebP 사진을 선택해 주세요.")
    if not data or len(data) > MAX_UPLOAD_BYTES:
        raise ValueError("사진은 50MB 이하로 선택해 주세요.")
    try:
        from PIL import ImageOps

        with Image.open(io.BytesIO(data)) as image:
            if image.format not in {"JPEG", "MPO", "PNG", "WEBP"}:
                raise ValueError("JPG, PNG, WebP 사진을 선택해 주세요.")
            image.verify()
            with Image.open(io.BytesIO(data)) as reopened:
                oriented = ImageOps.exif_transpose(reopened)
                if oriented.width * oriented.height > 80_000_000:
                    raise ValueError("사진 해상도가 너무 큽니다.")
    except (OSError, Image.DecompressionBombError) as error:
        raise ValueError("사진 파일을 읽을 수 없습니다.") from error
    return suffixes[content_type]


def parse_bbox_regions(raw: str) -> list[dict]:
    try:
        values = json.loads(raw)
    except json.JSONDecodeError as error:
        raise ValueError("수정 영역 좌표를 읽을 수 없습니다.") from error
    if not isinstance(values, list) or not 1 <= len(values) <= 20:
        raise ValueError("수정 영역은 1~20개까지 지정할 수 있습니다.")
    regions = []
    for item in values:
        if not isinstance(item, dict) or not isinstance(item.get("bbox"), list) or len(item["bbox"]) != 4:
            raise ValueError("각 수정 영역은 bbox 좌표 4개가 필요합니다.")
        target = item.get("target")
        main = item.get("mainDescription", target)
        sub = item.get("subDescription", "")
        risk = item.get("riskLevel", "")
        rationale = item.get("riskRationale", "")
        selected = item.get("selected", True)
        bbox = item["bbox"]
        if not isinstance(main, str) or not main.strip() or len(main.strip()) > 200:
            raise ValueError("각 단서의 주요 설명을 1~200자로 입력해 주세요.")
        if any(not isinstance(value, str) or len(value) > limit for value, limit in ((sub, 500), (risk, 80), (rationale, 500))):
            raise ValueError("부가 설명·위험도·산정 근거의 길이를 확인해 주세요.")
        if "mainDescription" in item and risk not in {"주의", "높음"}:
            raise ValueError("모의 분석 단서의 위험도는 주의 또는 높음으로 선택해 주세요.")
        if risk and risk not in {"주의", "높음"}:
            raise ValueError("위험도는 주의 또는 높음으로 입력해 주세요.")
        if not isinstance(selected, bool):
            raise ValueError("편집할 단서 선택 여부가 올바르지 않습니다.")
        if any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in bbox):
            raise ValueError("bbox 좌표는 숫자로 입력해 주세요.")
        x1, y1, x2, y2 = [float(value) for value in bbox]
        if (not all(math.isfinite(value) for value in (x1, y1, x2, y2))
                or not 0 <= x1 < x2 <= 1 or not 0 <= y1 < y2 <= 1):
            raise ValueError("bbox는 EXIF 보정 사진 기준 0~1 범위의 왼쪽 위·오른쪽 아래 좌표여야 합니다.")
        regions.append({
            "id": str(item.get("id", ""))[:80],
            "target": main.strip(),
            "mainDescription": main.strip(),
            "subDescription": sub.strip(),
            "riskLevel": risk.strip(),
            "riskRationale": rationale.strip(),
            "selected": selected,
            "bbox": [x1, y1, x2, y2],
        })
    return regions


def create_bbox_union_mask(width: int, height: int, regions: list[dict], destination: Path) -> None:
    """Create one OpenAI-compatible alpha mask from normalized [x1,y1,x2,y2] boxes."""
    alpha = Image.new("L", (width, height), 255)
    draw = ImageDraw.Draw(alpha)
    for region in regions:
        x1, y1, x2, y2 = region["bbox"]
        left = max(0, min(width - 1, math.floor(x1 * width)))
        top = max(0, min(height - 1, math.floor(y1 * height)))
        right = max(left + 1, min(width, math.ceil(x2 * width)))
        bottom = max(top + 1, min(height, math.ceil(y2 * height)))
        draw.rectangle((left, top, right - 1, bottom - 1), fill=0)
    mask = Image.new("RGBA", (width, height), (0, 0, 0, 255))
    mask.putalpha(alpha)
    mask.save(destination, format="PNG", optimize=True)
    if destination.stat().st_size > MAX_MASK_BYTES:
        destination.unlink(missing_ok=True)
        raise ValueError("생성된 mask가 4MB를 초과합니다. 사진 크기를 줄여 다시 시도해 주세요.")


def run_command(command: list[str], timeout: int) -> str:
    completed = subprocess.run(
        command,
        cwd=REPOSITORY_ROOT,
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    if completed.returncode != 0:
        detail = next((line.strip() for line in reversed(completed.stderr.splitlines()) if line.strip()), "")
        if not detail:
            detail = f"도구가 종료 코드 {completed.returncode}를 반환했습니다."
        # These scripts deliberately emit generic API errors and never print credentials or response bodies.
        raise RuntimeError(detail[:500])
    return completed.stdout


def run_mask_generation(session_id: str) -> None:
    directory = session_dir(session_id)
    manifest_path = directory / "session.json"
    manifest = read_manifest(manifest_path)
    started = time.monotonic()
    manifest["status"] = "masking"
    targets = manifest.get("targets") or [manifest["target"]]
    manual_bbox = manifest.get("mode") == "manual-bbox"
    manifest["message"] = (
        f"{len(manifest['bboxRegions'])}개 사각형 영역으로 mask를 만드는 중"
        if manual_bbox else f"{len(targets)}개 대상의 수정 위치를 각각 찾고 mask를 합치는 중"
    )
    write_manifest(directory, manifest)
    try:
        if manual_bbox:
            output_dir = directory / "mask-output" / "manual-bbox"
            output_dir.mkdir(parents=True, exist_ok=True)
            source_path = output_dir / "source.png"
            mask_path = output_dir / "mask.png"
            preview_path = output_dir / "mask-preview.png"
            width, height, _ = prepare_image(directory / manifest["inputFile"], source_path)
            selected_regions = [region for region in manifest["bboxRegions"] if region.get("selected", True)]
            if not selected_regions:
                raise ValueError("편집할 단서를 하나 이상 선택해 주세요.")
            create_bbox_union_mask(width, height, selected_regions, mask_path)
            create_preview(source_path, mask_path, preview_path)
            plan = {"confidence": None, "targets": selected_regions}
        else:
            command = [
                sys.executable,
                str(TOOL_ROOT / "mask_from_text.py"),
                "--image", str(directory / manifest["inputFile"]),
            ]
            for target in targets:
                command.extend(["--target", target])
            command.extend(["--model", MASK_MODEL, "--output-dir", str(directory / "mask-output")])
            run_command(command, timeout=180 * len(targets))
            mask_path = next((directory / "mask-output").rglob("mask.png"), None)
            if mask_path is None:
                raise RuntimeError("mask 생성 결과를 찾지 못했습니다.")
            preview_path = mask_path.with_name("mask-preview.png")
            plan = read_manifest(mask_path.with_name("mask-plan.json"))
        manifest = read_manifest(manifest_path)
        manifest.update({
            "status": "ready",
            "message": f"{len(targets)}개 대상의 mask가 준비됐습니다. 붉은 영역을 확인한 뒤 한 번에 편집하세요.",
            "maskFile": str(mask_path.relative_to(directory)),
            "maskPreviewFile": str(preview_path.relative_to(directory)),
            "maskConfidence": plan.get("confidence"),
            "targetMaskConfidences": plan.get("targets", []),
            "maskSource": "manual-bbox" if manual_bbox else "vision-generated-polygon",
            "maskModel": "사용자 지정 bbox" if manual_bbox else MASK_MODEL,
            "maskElapsedSeconds": round(time.monotonic() - started, 1),
        })
        write_manifest(directory, manifest)
    except Exception as error:  # Keep worker failures visible in the local page.
        manifest = read_manifest(manifest_path)
        manifest.update({
            "status": "failed",
            "message": str(error)[:500],
            "maskElapsedSeconds": round(time.monotonic() - started, 1),
        })
        write_manifest(directory, manifest)


def run_edit(session_id: str, edit_id: str) -> None:
    directory = session_dir(session_id)
    manifest_path = directory / "session.json"
    edit_path = directory / "edits" / f"{edit_id}.json"
    edit = read_manifest(edit_path)
    started = time.monotonic()
    edit.update({"status": "editing", "message": "선택한 모델로 사진을 편집하는 중"})
    edit_path.write_text(json.dumps(edit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try:
        manifest = read_manifest(manifest_path)
        command = [
                sys.executable,
                str(TOOL_ROOT / "evaluate.py"),
                "--image", str(directory / manifest["inputFile"]),
                "--mask-file", str(directory / manifest["maskFile"]),
                "--target", "; ".join(manifest.get("targets") or [manifest["target"]]),
                "--preserve", edit["preserve"],
                "--model", edit["model"],
                "--quality", edit["quality"],
        ]
        if edit["cropContextScale"]:
            command.extend(["--crop-context-scale", str(edit["cropContextScale"])])
        command.extend(["--output-dir", str(directory / "edit-output")])
        run_command(command, timeout=420)
        run_json = max((directory / "edit-output").rglob("run.json"), key=lambda path: path.stat().st_mtime)
        run_metadata = read_manifest(run_json)
        result_file = run_json.with_name("edited.png")
        if not result_file.is_file():
            raise RuntimeError("편집 결과 이미지를 찾지 못했습니다.")
        edit.update({
            "status": "completed",
            "message": "완료",
            "resultFile": str(result_file.relative_to(directory)),
            "prompt": run_metadata.get("prompt", ""),
            "elapsedSeconds": round(time.monotonic() - started, 1),
        })
    except Exception as error:
        edit.update({
            "status": "failed",
            "message": str(error)[:500],
            "elapsedSeconds": round(time.monotonic() - started, 1),
        })
    edit_path.write_text(json.dumps(edit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest = read_manifest(manifest_path)
    manifest["edits"] = [
        read_manifest(path) for path in sorted((directory / "edits").glob("*.json"), key=lambda item: item.stat().st_mtime)
    ]
    write_manifest(directory, manifest)


class EditEvalHandler(BaseHTTPRequestHandler):
    server_version = "KkokKkokLocalEditEval/1.0"

    def log_message(self, format: str, *args: object) -> None:
        # Avoid logging local filenames, prompt text, or image-upload request details.
        return

    def end_headers(self) -> None:
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-store")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data: blob:; style-src 'self'; script-src 'self'; "
            "connect-src 'self'; frame-ancestors 'none'",
        )
        super().end_headers()

    def send_json(self, status: int, data: dict | list) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_body(self, limit: int) -> bytes:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ValueError("요청 크기를 확인할 수 없습니다.") from None
        if length <= 0 or length > limit:
            raise ValueError("요청 크기가 허용 범위를 벗어났습니다.")
        return self.rfile.read(length)

    def verify_origin(self) -> None:
        origin = self.headers.get("Origin")
        if not origin:
            return
        parsed = urlsplit(origin)
        allowed = {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"}
        if parsed.scheme != "http" or parsed.netloc not in allowed:
            raise PermissionError("이 페이지에서 시작한 요청만 허용됩니다.")

    def do_GET(self) -> None:
        parsed = urlsplit(self.path)
        if parsed.path == "/":
            self.send_static("index.html", "text/html; charset=utf-8")
            return
        if parsed.path == "/results":
            self.send_static("results.html", "text/html; charset=utf-8")
            return
        if parsed.path in {"/app.js", "/results.js", "/style.css"}:
            name = parsed.path.lstrip("/")
            content_type = "text/javascript; charset=utf-8" if name.endswith(".js") else "text/css; charset=utf-8"
            self.send_static(name, content_type)
            return
        if parsed.path == "/api/models":
            self.send_json(200, {"imageModels": sorted(IMAGE_MODELS), "maskModel": MASK_MODEL})
            return
        if parsed.path == "/api/sessions":
            sessions = []
            if OUTPUT_ROOT.exists():
                for path in OUTPUT_ROOT.glob("*/session.json"):
                    try:
                        sessions.append(public_manifest(read_manifest(path)))
                    except (OSError, json.JSONDecodeError):
                        continue
            sessions.sort(key=lambda item: item.get("updatedAt", ""), reverse=True)
            self.send_json(200, sessions[:50])
            return
        if parsed.path.startswith("/api/sessions/"):
            parts = parsed.path.strip("/").split("/")
            if len(parts) == 4 and parts[0:2] == ["api", "sessions"] and parts[3] == "file":
                self.send_session_file(parts[2], parse_qs(parsed.query))
                return
            if len(parts) == 3 and parts[0:2] == ["api", "sessions"]:
                self.send_session(parts[2])
                return
        self.send_json(404, {"error": "요청한 경로를 찾을 수 없습니다."})

    def send_static(self, name: str, content_type: str) -> None:
        path = (WEB_ROOT / name).resolve()
        if not path.is_relative_to(WEB_ROOT.resolve()) or not path.is_file():
            self.send_json(404, {"error": "페이지 파일을 찾을 수 없습니다."})
            return
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_session(self, session_id: str) -> None:
        try:
            path = session_dir(session_id) / "session.json"
            self.send_json(200, public_manifest(read_manifest(path)))
        except (ValueError, OSError, json.JSONDecodeError):
            self.send_json(404, {"error": "실행 기록을 찾을 수 없습니다."})

    def send_session_file(self, session_id: str, query: dict[str, list[str]]) -> None:
        try:
            directory = session_dir(session_id)
            manifest = read_manifest(directory / "session.json")
            kind = query.get("kind", [""])[0]
            if kind == "source":
                relative = manifest["inputFile"]
            elif kind == "mask":
                relative = manifest.get("maskFile")
            elif kind == "mask-preview":
                relative = manifest.get("maskPreviewFile")
            elif kind == "result":
                edit_id = query.get("edit", [""])[0]
                if not EDIT_ID.fullmatch(edit_id):
                    raise ValueError
                edit = read_manifest(directory / "edits" / f"{edit_id}.json")
                relative = edit.get("resultFile")
            else:
                raise ValueError
            if not relative:
                raise ValueError
            path = (directory / relative).resolve()
            if not path.is_relative_to(directory.resolve()) or not path.is_file():
                raise ValueError
            content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
            body = path.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (ValueError, OSError, KeyError, json.JSONDecodeError):
            self.send_json(404, {"error": "결과 파일을 찾을 수 없습니다."})

    def do_POST(self) -> None:
        try:
            self.verify_origin()
            parsed = urlsplit(self.path)
            if parsed.path == "/api/sessions":
                self.create_session()
                return
            match = re.fullmatch(r"/api/sessions/([a-f0-9]{16})/edits", parsed.path)
            if match:
                self.create_edit(match.group(1))
                return
            self.send_json(404, {"error": "요청한 경로를 찾을 수 없습니다."})
        except PermissionError as error:
            self.send_json(403, {"error": str(error)})
        except ValueError as error:
            self.send_json(400, {"error": str(error)})
        except (OSError, json.JSONDecodeError):
            self.send_json(400, {"error": "요청을 처리하지 못했습니다."})

    def create_session(self) -> None:
        if self.headers.get_content_type() != "multipart/form-data":
            raise ValueError("사진 업로드 형식이 올바르지 않습니다.")
        fields, upload = parse_form(self.read_body(MAX_BODY_BYTES), self.headers.get("Content-Type", ""))
        mode = fields.get("mode", "text")
        if mode not in {"text", "manual-bbox"}:
            raise ValueError("수정 영역 지정 방식을 확인해 주세요.")
        bbox_regions = []
        if mode == "manual-bbox":
            bbox_regions = parse_bbox_regions(fields.get("regions", ""))
            targets = [
                ": ".join(value for value in (region["mainDescription"], region["subDescription"]) if value)
                for region in bbox_regions if region["selected"]
            ]
            if not targets:
                raise ValueError("편집할 단서를 하나 이상 선택해 주세요.")
            target = " / ".join(targets)
        else:
            raw_target = fields.get("target", "")
            targets = list(dict.fromkeys(
                line.strip().lstrip("•-* ").strip()
                for line in raw_target.splitlines()
                if line.strip().lstrip("•-* ").strip()
            ))
            if not targets or len(raw_target) > 1500:
                raise ValueError("수정 대상을 한 줄에 하나씩 1~1,500자로 입력해 주세요.")
            if len(targets) > 8:
                raise ValueError("한 번에 지정할 수 있는 수정 대상은 최대 8개입니다.")
            target = " / ".join(targets)
        if upload is None:
            raise ValueError("편집할 사진을 선택해 주세요.")
        content_type, data = upload
        suffix = validate_image(content_type, data)
        session_id = uuid.uuid4().hex[:16]
        directory = session_dir(session_id)
        (directory / "edits").mkdir(parents=True, exist_ok=False)
        input_file = f"input{suffix}"
        (directory / input_file).write_bytes(data)
        manifest = {
            "id": session_id,
            "status": "queued",
            "message": "수정 영역 mask를 준비하는 중",
            "mode": mode,
            "analysisMode": "mock" if mode == "manual-bbox" else None,
            "mockNotice": "모의 분석 결과입니다. 실제 분석 결과나 확정된 API 형식이 아닙니다." if mode == "manual-bbox" else None,
            "target": target,
            "targets": targets,
            "bboxRegions": bbox_regions,
            "maskModel": MASK_MODEL,
            "inputFile": input_file,
            "createdAt": utc_now(),
            "edits": [],
        }
        write_manifest(directory, manifest)
        JOBS.submit(run_mask_generation, session_id)
        self.send_json(202, public_manifest(manifest))

    def create_edit(self, session_id: str) -> None:
        directory = session_dir(session_id)
        manifest = read_manifest(directory / "session.json")
        if manifest.get("status") != "ready":
            raise ValueError("mask 생성이 끝난 뒤 편집을 실행해 주세요.")
        request = json.loads(self.read_body(16 * 1024).decode("utf-8"))
        model = str(request.get("model", ""))
        quality = str(request.get("quality", ""))
        preserve = str(request.get("preserve", "")).strip()
        try:
            crop_scale = float(request.get("cropContextScale", 0))
        except (TypeError, ValueError):
            raise ValueError("편집 범위 숫자가 올바르지 않습니다.") from None
        if model not in IMAGE_MODELS:
            raise ValueError("지원되는 이미지 편집 모델을 선택해 주세요.")
        if quality not in QUALITIES:
            raise ValueError("지원되는 품질 설정을 선택해 주세요.")
        if quality in {"xhigh", "max"} and model not in EXTRA_QUALITY_MODELS:
            raise ValueError("매우 높음·최대 품질은 GPT Image 2.5 모델에서 선택할 수 있습니다.")
        if len(preserve) > 2000:
            raise ValueError("보존할 내용은 2,000자 이하로 입력해 주세요.")
        if crop_scale != 0 and not 1.5 <= crop_scale <= 8:
            raise ValueError("편집 문맥 크기는 1.5~8 범위로 선택해 주세요.")
        edit_id = uuid.uuid4().hex[:16]
        edit = {
            "id": edit_id,
            "status": "queued",
            "message": "편집 요청을 기다리는 중",
            "model": model,
            "quality": quality,
            "preserve": preserve,
            "cropContextScale": crop_scale,
            "createdAt": utc_now(),
        }
        edit_path = directory / "edits" / f"{edit_id}.json"
        edit_path.write_text(json.dumps(edit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        manifest["edits"] = [
            read_manifest(path) for path in sorted((directory / "edits").glob("*.json"), key=lambda item: item.stat().st_mtime)
        ]
        write_manifest(directory, manifest)
        JOBS.submit(run_edit, session_id, edit_id)
        self.send_json(202, edit)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="브라우저에서 보호 편집 품질을 반복 비교합니다.")
    parser.add_argument("--host", default="127.0.0.1", choices=("127.0.0.1", "localhost"))
    parser.add_argument("--port", type=int, default=8765)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer((args.host, args.port), EditEvalHandler)
    print(f"꼭꼭 보호 편집 테스트 페이지: http://{args.host}:{args.port}")
    print("브라우저를 닫으면 Ctrl+C로 서버를 종료할 수 있습니다. 결과는 Downloads에 저장됩니다.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        JOBS.shutdown(wait=False, cancel_futures=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
