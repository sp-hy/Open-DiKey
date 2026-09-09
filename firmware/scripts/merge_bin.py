# Merge bootloader + partitions + app into one image at 0x0 for ESP Web Tools.
Import("env")

from pathlib import Path


def _boot_app0(env):
    pkg = Path(env.subst("$PROJECT_PACKAGES_DIR"))
    matches = list(pkg.glob("framework-arduinoespressif32*/tools/partitions/boot_app0.bin"))
    if not matches:
        matches = list(pkg.glob("**/boot_app0.bin"))
    if not matches:
        raise FileNotFoundError("boot_app0.bin not found under PlatformIO packages")
    return matches[0]


def merge_bin(source, target, env):
    build_dir = Path(env.subst("$BUILD_DIR"))
    project_dir = Path(env.subst("$PROJECT_DIR"))
    app = Path(env.subst("$BUILD_DIR/${PROGNAME}.bin"))
    bootloader = build_dir / "bootloader.bin"
    partitions = build_dir / "partitions.bin"
    boot_app0 = _boot_app0(env)
    out = project_dir / "dikey-usb-bridge-c3.bin"

    tool = Path(env.subst("$PROJECT_PACKAGES_DIR")) / "tool-esptoolpy"
    esptool = tool / "esptool.py"
    if not esptool.exists():
        esptool = tool / "esptool"

    cmd = " ".join(
        [
            "$PYTHONEXE",
            f'"{esptool}"',
            "--chip",
            "esp32c3",
            "merge_bin",
            "-o",
            f'"{out}"',
            "--flash_mode",
            "dio",
            "--flash_size",
            "4MB",
            "0x0",
            f'"{bootloader}"',
            "0x8000",
            f'"{partitions}"',
            "0xe000",
            f'"{boot_app0}"',
            "0x10000",
            f'"{app}"',
        ]
    )
    env.Execute(env.VerboseAction(cmd, f"Merging web-flash image → {out}"))


env.AddPostAction("$BUILD_DIR/${PROGNAME}.bin", merge_bin)
