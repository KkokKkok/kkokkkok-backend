import argparse
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image

import evaluate
from evaluate import Region, create_mask, prepare_image, run, validate_paths


class RegionTests(unittest.TestCase):
    def test_parses_normalized_region(self):
        self.assertEqual(Region.parse("0.1, 0.2, 0.3, 0.4"), Region(.1, .2, .3, .4))

    def test_rejects_region_outside_image(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            Region.parse("0.9,0.2,0.2,0.4")


class ImagePreparationTests(unittest.TestCase):
    def test_mask_has_transparent_edit_area_and_opaque_background(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mask.png"
            pixels = create_mask(100, 80, Region(.2, .25, .3, .25), path)
            self.assertEqual(pixels, (20, 20, 50, 40))
            with Image.open(path) as mask:
                self.assertEqual(mask.size, (100, 80))
                self.assertEqual(mask.getpixel((30, 30))[3], 0)
                self.assertEqual(mask.getpixel((10, 10))[3], 255)

    def test_prepare_image_applies_exif_orientation_and_strips_exif(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.jpg"
            cleaned = Path(directory) / "cleaned.png"
            image = Image.new("RGB", (40, 20), (20, 80, 120))
            exif = Image.Exif()
            exif[274] = 6  # Rotate 90 degrees clockwise when displayed.
            exif[271] = "synthetic-camera"
            image.save(source, exif=exif)

            width, height, digest = prepare_image(source, cleaned)

            self.assertEqual((width, height), (20, 40))
            self.assertEqual(len(digest), 64)
            with Image.open(cleaned) as output:
                self.assertEqual(output.format, "PNG")
                self.assertEqual(len(output.getexif()), 0)


class SafetyAndRunTests(unittest.TestCase):
    def test_loads_key_from_local_env_file_without_overriding_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".env").write_text('# local only\nOPENAI_API_KEY="test-key"\nOTHER=value\n', encoding="utf-8")
            with patch.object(evaluate, "REPOSITORY_ROOT", root), patch.dict("os.environ", {}, clear=True):
                evaluate.load_local_api_key()
                self.assertEqual(evaluate.os.environ["OPENAI_API_KEY"], "test-key")
            with patch.object(evaluate, "REPOSITORY_ROOT", root), patch.dict(
                "os.environ", {"OPENAI_API_KEY": "shell-key"}, clear=True
            ):
                evaluate.load_local_api_key()
                self.assertEqual(evaluate.os.environ["OPENAI_API_KEY"], "shell-key")

    def test_rejects_repo_paths_for_photo_or_output(self):
        with tempfile.NamedTemporaryFile(dir=Path(__file__).resolve().parents[2]) as photo:
            with self.assertRaises(ValueError):
                validate_paths(Path(photo.name), Path(tempfile.gettempdir()))
        with tempfile.TemporaryDirectory() as directory:
            outside_photo = Path(directory) / "photo.png"
            Image.new("RGB", (8, 8)).save(outside_photo)
            with self.assertRaisesRegex(ValueError, "저장소 바깥"):
                validate_paths(outside_photo, Path(__file__).resolve().parents[2] / "private-data")

    def test_dry_run_keeps_all_artifacts_outside_repo(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            photo = root / "synthetic.png"
            Image.new("RGB", (32, 24), (120, 140, 160)).save(photo)
            args = type("Args", (), {
                "image": photo,
                "output_dir": root / "results",
                "region": Region(.25, .25, .5, .5),
                "target": "synthetic sign",
                "preserve": "synthetic cup",
                "model": "gpt-image-2.5-sunburst",
                "quality": "high",
                "dry_run": True,
            })()

            result = run(args)

            self.assertEqual({p.name for p in result.iterdir()}, {"source.png", "mask.png", "mask-preview.png", "run.json", "review.md"})
            manifest = json.loads((result / "run.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["status"], "dry-run")
            self.assertNotIn(str(photo), (result / "run.json").read_text(encoding="utf-8"))

    def test_requires_api_key_before_copying_photo(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            photo = root / "synthetic.png"
            Image.new("RGB", (32, 24), (120, 140, 160)).save(photo)
            args = type("Args", (), {
                "image": photo,
                "output_dir": root / "results",
                "region": Region(.25, .25, .5, .5),
                "target": "synthetic sign",
                "preserve": "synthetic cup",
                "model": "gpt-image-2.5-sunburst",
                "quality": "high",
                "dry_run": False,
            })()
            with patch.dict("os.environ", {"OPENAI_API_KEY": ""}):
                with self.assertRaisesRegex(ValueError, "OPENAI_API_KEY"):
                    run(args)
            self.assertFalse((root / "results").exists())


if __name__ == "__main__":
    unittest.main()
