"""
punta_a_punta.py — El servidor de la prueba de punta a punta (`PuntaAPuntaTest`).

Monta en una carpeta de trabajo un remoto de prueba —un catálogo con una pareja
bisync `notas`, un espejo de bajada `fotos` y el llavero (`[keychain]`), con
ficheros ya dentro y una base de KeePassXC entera en `keychain/`—, una clave
para el teléfono y otra para el servidor, y la carga del QR que las junta, hecha
con el `pairing.dumps()` de prdrive (y comprobada con su `leer()`). Después
arranca `rclone serve sftp` sobre el remoto, escuchando SOLO en 127.0.0.1: el
emulador ve el PC en 10.0.2.2.

    python herramientas/punta_a_punta.py ../prdrive <rclone> <carpeta de trabajo> [puerto]

`<rclone>` es un rclone de la versión del .aar o cercana (prdrive la fija en
`common/pins.py`): aquí solo sirve SFTP, y bisync corre en el teléfono con el
del .aar. Mientras corre, en otra consola, la orden que imprime lanza la
prueba. Necesita `ssh-keygen` (OpenSSH).
"""

from __future__ import annotations

import base64
import shutil
import subprocess
import sys
from pathlib import Path

CATALOGO = """\
# Catálogo de la prueba de punta a punta de prdrive-app.

[remote]
name = "nas"
type = "sftp"

[defaults]
remote = "nas"
device_remote = "disp"

[[pair]]
name = "notas"
local = "sync-data/notas"
remote_path = "/copia/notas"

[[pair]]
name = "fotos"
local = "sync-data/fotos"
remote_path = "/copia/fotos"
mode = "down-mirror"

[keychain]
base = "personal.kdbx"
"""

# Lo que `PuntaAPuntaTest` espera encontrar en el remoto.
FICHEROS = {
    "copia/notas/del-pc.txt": "escrito en el PC\n",
    # Uno que no cambia: con un solo fichero, cambiarlo es cambiarlos todos y
    # bisync se para por seguridad.
    "copia/notas/quieto.txt": "quieto\n",
    "copia/fotos/foto.txt": "una foto\n",
}


def base_entera(prdrive: Path) -> bytes:
    """
    Los bytes de una base de KeePassXC entera, fabricada como lo hace
    `vectores_llavero.py` (la cabecera de KDBX 4 con su suma y su cadena de
    bloques) y comprobada con el `kdbx.mirar()` de prdrive: son los mismos
    bytes que `Banco.base(entera = true)` saca de los vectores, y con eso
    `PuntaAPuntaTest` sabe qué tiene que encontrar en el teléfono. Sin una
    base entera, la pasada del llavero la dejaría fuera.
    """
    sys.path.insert(0, str(prdrive))
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from common import kdbx  # noqa: PLC0415
    import vectores_llavero  # noqa: PLC0415

    datos = vectores_llavero._kdbx4(kdbx, [b"x" * 20, b"y" * 8])
    mirada = kdbx.mirar(datos)
    assert mirada.entera, mirada.motivo
    return datos


def preparar(prdrive: Path, trabajo: Path, puerto: int) -> Path:
    """Deja el remoto, las claves y la carga en [trabajo]; devuelve la carga en base64."""
    remoto, claves = trabajo / "remoto", trabajo / "claves"
    # El remoto, de cero cada vez: lo que subió una prueba anterior haría pasar
    # la comprobación de que esta ha subido lo suyo. Las claves sí se quedan.
    shutil.rmtree(remoto, ignore_errors=True)
    (remoto / "prdrive-catalog").mkdir(parents=True, exist_ok=True)
    (remoto / "prdrive-catalog" / "remote.toml").write_text(CATALOGO, encoding="utf-8", newline="\n")
    for ruta, texto in FICHEROS.items():
        (remoto / ruta).parent.mkdir(parents=True, exist_ok=True)
        (remoto / ruta).write_text(texto, encoding="utf-8", newline="\n")
    # La carpeta del llavero cuelga de la del catálogo (`LLAVERO_REMOTO`).
    (remoto / "prdrive-catalog" / "keychain").mkdir(parents=True, exist_ok=True)
    (remoto / "prdrive-catalog" / "keychain" / "personal.kdbx").write_bytes(base_entera(prdrive))
    claves.mkdir(parents=True, exist_ok=True)
    for nombre, comentario in (("id_ed25519", "prdrive-app e2e"), ("host_ed25519", "servidor e2e")):
        if not (claves / nombre).exists():
            subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", comentario,
                            "-f", str(claves / nombre)], check=True)
    (claves / "authorized_keys").write_bytes((claves / "id_ed25519.pub").read_bytes())

    sys.path.insert(0, str(prdrive))
    from common import pairing  # noqa: PLC0415

    host = (claves / "host_ed25519.pub").read_text(encoding="ascii").split()
    texto = pairing.dumps(
        "nas",
        {"type": "sftp", "host": "10.0.2.2", "port": str(puerto), "user": "prueba"},
        key_name="id_ed25519",
        catalog_path="/prdrive-catalog/remote.toml",
        private_key=(claves / "id_ed25519").read_bytes(),
        known_hosts=f"[10.0.2.2]:{puerto} {host[0]} {host[1]}\n",
    )
    assert pairing.leer(texto).remote_name == "nas"
    b64 = trabajo / "carga.b64"
    b64.write_text(base64.b64encode(texto.encode("utf-8")).decode("ascii"), encoding="ascii")
    return b64


def main() -> int:
    if len(sys.argv) not in (4, 5):
        print(__doc__.strip().split("\n\n")[-2])
        return 2
    prdrive, rclone, trabajo = Path(sys.argv[1]).resolve(), sys.argv[2], Path(sys.argv[3]).resolve()
    puerto = int(sys.argv[4]) if len(sys.argv) == 5 else 2022
    b64 = preparar(prdrive, trabajo, puerto)
    print("Carga del QR en", b64)
    print("En otra consola, con el emulador arrancado:\n")
    print("    ./gradlew :app:connectedDebugAndroidTest \\\n"
          "        -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.PuntaAPuntaTest \\\n"
          f"        -Pandroid.testInstrumentationRunnerArguments.carga_b64=$(cat '{b64.as_posix()}')\n")
    claves = trabajo / "claves"
    return subprocess.call([
        rclone, "serve", "sftp", str(trabajo / "remoto"),
        "--addr", f"127.0.0.1:{puerto}",
        "--authorized-keys", str(claves / "authorized_keys"),
        "--key", str(claves / "host_ed25519"),
        "--config", str(trabajo / "sin-config.conf"),
        "-v",
    ])


if __name__ == "__main__":
    sys.exit(main())
