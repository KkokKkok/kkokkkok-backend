#!/usr/bin/env python3
"""Keep the original photo outside an edit mask and use AI pixels only inside it."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Sequence

from PIL import Image, ImageChops, ImageFilter, ImageOps

from evaluate import REPOSITORY_ROOT, is_within


def load_rgb(path: Path) -> tuple[Image.Image, bytes | None]:
    with Image.open(path) as opened:
        icc_profile = opened.info.get("icc_profile")
        return ImageOps.exif_transpose(opened).convert("RGB"), icc_profile


def composite_preserved(
    source: Image.Image, edited: Image.Image, mask: Image.Image, feather_px: int = 12, expand_px: int = 0
) -> Image.Image:
    """Composite edited pixels inside transparent mask pixels, preserving the rest exactly."""
    if source.size != mask.size:
        raise ValueError("원본 사진과 mask 크기가 같아야 합니다.")
    if feather_px < 0 or feather_px > 256:
        raise ValueError("경계 혼합 값은 0~256px 범위여야 합니다.")
    if expand_px < 0 or expand_px > 256:
        raise ValueError("mask 확장 값은 0~256px 범위여야 합니다.")
    source_ratio = source.width / source.height
    edited_ratio = edited.width / edited.height
    if abs(source_ratio - edited_ratio) > 0.005:
        raise ValueError("편집 결과와 원본의 가로·세로 비율이 달라 합성할 수 없습니다.")
    if edited.size != source.size:
        edited = edited.resize(source.size, Image.Resampling.LANCZOS)

    editable = ImageOps.invert(mask.convert("RGBA").getchannel("A"))
    if expand_px:
        editable = editable.filter(ImageFilter.MaxFilter(expand_px * 2 + 1))
    if feather_px:
        blurred = editable.filter(ImageFilter.GaussianBlur(radius=feather_px))
        # Feather inward only: no pixels outside the original mask can change.
        editable = ImageChops.darker(blurred, editable)
    return Image.composite(edited, source, editable)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="AI 결과를 mask 안에만 합성하고 나머지는 원본으로 보존합니다.")
    parser.add_argument("--source", type=Path, required=True, help="EXIF 방향이 자동 보정되는 원본 사진")
    parser.add_argument("--edited", type=Path, required=True, help="이미지 편집 모델이 반환한 결과 사진")
    parser.add_argument("--mask", type=Path, required=True, help="투명 영역이 편집 대상인 alpha PNG")
    parser.add_argument("--output", type=Path, required=True, help="저장소 바깥의 합성 결과 PNG 경로")
    parser.add_argument("--expand-px", type=int, default=0,
                        help="편집 영역을 mask 바깥으로 확장 (확장 영역은 원본과 달라질 수 있음)")
    parser.add_argument("--feather-px", type=int, default=12, help="mask 안쪽 경계 혼합 폭 (기본 12px, 0은 끔)")
    return parser


def run(args: argparse.Namespace) -> Path:
    source_path = args.source.expanduser().resolve()
    edited_path = args.edited.expanduser().resolve()
    mask_path = args.mask.expanduser().resolve()
    output_path = args.output.expanduser().resolve()
    for input_path in (source_path, edited_path, mask_path):
        if not input_path.is_file():
            raise ValueError("입력 파일을 찾을 수 없습니다.")
        if is_within(input_path, REPOSITORY_ROOT):
            raise ValueError("실제 사진과 편집 결과는 저장소 밖 경로에서 선택해 주세요.")
    if is_within(output_path, REPOSITORY_ROOT):
        raise ValueError("합성 결과는 저장소 바깥 경로에만 저장할 수 있습니다.")

    source, icc_profile = load_rgb(source_path)
    edited, _ = load_rgb(edited_path)
    with Image.open(mask_path) as opened_mask:
        if "A" not in opened_mask.getbands():
            raise ValueError("mask PNG에 alpha 채널이 필요합니다.")
        mask = ImageOps.exif_transpose(opened_mask).convert("RGBA")
    result = composite_preserved(source, edited, mask, args.feather_px, args.expand_px)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    result.save(output_path, format="PNG", optimize=True, icc_profile=icc_profile)
    return output_path


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        output = run(args)
    except (OSError, ValueError) as error:
        print(f"오류: {error}", file=sys.stderr)
        return 2
    print(f"mask 영역을 AI 편집 결과로 합성했습니다: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
