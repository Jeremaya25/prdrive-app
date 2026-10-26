# Handoff: Probar en un emulador «el guardado sobre una versión vieja va aparte» (llavero)

## Session Metadata
- Created: 2026-10-26 08:21:16
- Project: /home/user/prdrive-app (la ruta es la del contenedor de la sesión que lo escribió: en tu máquina, tu clon de `Jeremaya25/prdrive-app`)
- Branch: ccr-9c5adb27-393d23
- Session duration: unas 4 horas, repartidas en dos días

### Recent Commits (for context)
  - 311cdb7 Un guardado del llavero sobre una versión vieja va a una copia de conflicto
  - 8282ada Merge pull request #16 from Jeremaya25/ccr-66157d74-kfg47c
  - f91a393 VERSION 0.3.1: la llave del llavero llega a KeePassDX
  - ca4c1ba «Abrir llavero» le da a KeePassDX la llave de un llavero sin contraseña
  - 9b86dc4 Merge pull request #14 from Jeremaya25/claude/keychain-functionality-1e426a

## Handoff Chain

- **Continues from**: None (fresh start)
- **Supersedes**: None

> This is the first handoff for this task.

## Current State Summary

El commit `311cdb7` (rama `ccr-9c5adb27-393d23`, ya en `origin`) implementa la «opción D» del llavero: si una app de KeePass guarda la base sobre una versión que ya no es la del disco, el guardado no va encima, va a una copia de conflicto avisada. Compila sin avisos (`:app:compileDebugKotlin` y `:app:compileDebugAndroidTestKotlin`), el motor pasa sus 152 pruebas, y la lógica de `Lecturas.kt` se ha ejecutado en la JVM con 15 casos (con un `android.util.Log` falso), todos bien. **Lo que falta es lo que esta sesión no podía hacer, porque su contenedor no tenía KVM**: pasar las cinco pruebas nuevas de `ProveedorLlaveroTest` (y la clase entera, por si se ha roto algo) en un emulador, y hacer la prueba a mano con KeePassDX 4.5.6. No hay PR abierto ni se ha subido `VERSION`: la persona decidirá después de las pruebas.

## Codebase Understanding

### Architecture Overview

- `engine/` es Kotlin puro y espejo de prdrive (Python); `app/` es Android y «solo dibuja y llama». Lee `AGENTS.md` entero antes de tocar nada: sobre todo «El llavero» (está la regla nueva, «Un guardado sobre una versión vieja va aparte») y «Órdenes».
- Las apps de KeePass abren `.keychain/<base>.kdbx` por `VolumenProvider` (un `DocumentsProvider`). Leer con `"r"` pasa ahora por `leerBase()`, que apunta la huella SHA-256 de lo servido, por app (`callingPackage`) y por base, en `state/llavero-lecturas.json`. Guardar pasa por `GuardadoAtomico` (un temporal en `.prdrive/tmp/` que se pone de golpe al cerrar), y al cerrar pregunta a `Lecturas.destino()` adónde va: la base, o una copia `<base>.conflicto-dispositivo<AAAAMMDDhhmmss>.kdbx`.
- Después de apartar: `Conflictos.actualizarPareja` (la fila lo enseña sin esperar a la pasada), `Avisos.guardadoAparte` (notificación, solo con una copia nueva; queda como avisada para que la pasada no la repita) y `Disparos.alGuardar` (sube la copia).

### Critical Files

| File | Purpose | Relevance |
|------|---------|-----------|
| app/src/main/kotlin/prdrive/app/documentos/Lecturas.kt | Lo que se sabe de cada app y base; `destino()` (:133) decide adónde va el guardado; `nombreAparte()` (:160) | El fichero nuevo: la decisión entera |
| app/src/main/kotlin/prdrive/app/documentos/VolumenProvider.kt | `leerBase()` (:283), `quienLlama()` (:269), `guardarBase()` (:313), `apartado()` (:352) | Dónde se apunta y qué pasa al apartar |
| app/src/main/kotlin/prdrive/app/documentos/GuardadoAtomico.kt | El cierre pregunta `destino` (:199) y lo filtra con `elegir()` (:232) | Mueve el temporal a la base o a la copia |
| app/src/main/kotlin/prdrive/app/llavero/Avisos.kt | `guardadoAparte()` (:135) y el texto `GUARDADO_APARTE` | La notificación |
| app/src/androidTest/kotlin/prdrive/app/ProveedorLlaveroTest.kt | Las cinco pruebas nuevas (:238, :263, :286, :299, :313) | Lo que hay que pasar en el emulador |
| app/src/androidTest/kotlin/prdrive/app/Banco.kt | `terceraBase()`, nueva | Una tercera base entera, de los vectores |
| AGENTS.md | «El llavero» → «Un guardado sobre una versión vieja va aparte» | La regla y su porqué |
| PLAN.md | §1, el punto «KeePassDX ve el cambio en disco, pero se podía pisar (arreglado)» | Qué queda por ver en un teléfono |

### Key Patterns Discovered

- Comentarios, KDoc y textos en español; los tests de `androidTest` con guiones bajos y una frase que diga qué garantizan.
- Ningún valor esperado escrito a mano: salen de los vectores (`Banco.base`, `Banco.otraBase`, `Banco.terceraBase` leen `vectores.json`).
- En el proveedor, lo que viene después del cierre corre en el hilo `guardado-atomico`. Las pruebas esperan a sus efectos (`esperar { ... }`), no al temporal: `esperarAlCierre()` vuelve en cuanto el temporal se ha movido, antes de que acabe `alPoner`.

## Work Completed

### Tasks Finished

- [x] Visto en el código de KeePassDX 4.5.6 que detecta el cambio sin leer la base (`checkDatabaseInfo` compara fecha y tamaño) y que guardar no lee (`SaveDatabaseRunnable`: `"wt"`, o `"rwt"` si falla); abrir, recargar y combinar sí leen
- [x] `Lecturas.kt`, y los cambios en `VolumenProvider`, `GuardadoAtomico` y `Avisos`
- [x] Cinco pruebas nuevas en `ProveedorLlaveroTest` y `Banco.terceraBase()`
- [x] `AGENTS.md` y `PLAN.md` al día
- [x] Compila (app y pruebas), `:engine:test` en verde, `Lecturas` probado en la JVM
- [x] Comprobado con el prdrive de Python que `conflicts.leer_nombre` reconoce `personal.conflicto-dispositivo20261026153012.kdbx` como copia de `personal.kdbx`, de path1
- [x] Aparte, en prdrive: el issue Jeremaya25/prdrive#174 con los siete hallazgos de `PLAN.md` §2 (no es parte de esto)

### Files Modified

| File | Changes | Rationale |
|------|---------|-----------|
| app/src/main/kotlin/prdrive/app/documentos/Lecturas.kt | Nuevo | Apuntar qué versión tiene cada app y decidir el destino |
| app/src/main/kotlin/prdrive/app/documentos/VolumenProvider.kt | `leerBase`, `quienLlama`, `destino` y `alPoner(puesto)` en `guardarBase`, `apartado` | Enganchar `Lecturas` a leer y guardar |
| app/src/main/kotlin/prdrive/app/documentos/GuardadoAtomico.kt | Parámetro `destino`; `alPoner` recibe dónde ha ido | Que el cierre pueda poner en una copia |
| app/src/main/kotlin/prdrive/app/llavero/Avisos.kt | `GUARDADO_APARTE` y `guardadoAparte()` | La notificación de la copia |
| app/src/androidTest/kotlin/prdrive/app/ProveedorLlaveroTest.kt | Cinco pruebas, `leer()` y `copias()` | Cubrir la D |
| app/src/androidTest/kotlin/prdrive/app/Banco.kt | `terceraBase()` | Hacían falta tres bases distintas |
| AGENTS.md, PLAN.md | La regla nueva y el estado | Documentación |

### Decisions Made

| Decision | Options Considered | Rationale |
|----------|-------------------|-----------|
| Opción D: apartar el guardado viejo en el proveedor | A dejarlo, B «Sincronizar» sin llavero, C solo avisar, D detectar el guardado viejo | La única que cubre todas las vías («Sincronizar», «Abrir llavero», los recientes de KeePassDX) y deja la garantía del escritorio: nunca se pisa en silencio. La eligió la persona |
| Solo cuenta como lectura `"r"` | Contar también `"rw"` | Con `"rw"` no se sabe si la app lee: contarlo dejaría pasar un guardado viejo. No contarlo, como mucho, aparta uno bueno |
| Sin nada apuntado, o si no se puede apuntar, va encima | Fallar cerrado | Fallar cerrado apartaría todos los guardados de una app que abrió la base antes de esta versión |
| Huella SHA-256 del contenido | Inodo, tamaño y fecha | rclone y el guardado atómico cambian el inodo; una pasada que trae lo mismo no debe contar como cambio |
| Número de la copia = fecha y hora | El primer libre, como `copia_propia()` de prdrive | Dos dispositivos que apartan a la vez sacarían los dos un `1`, y bisync haría una copia de la copia, que «Combinar» no ofrece |
| prdrive (Python) no cambia | Hacer lo mismo en el escritorio | En el PC la copia es una copia de conflicto más, y KeePassXC ya recarga o combina |

## Pending Work

### Immediate Next Steps

1. En una máquina con emulador (API 37 x86_64, el de siempre) y el `.aar` construido (`rclone/construir.sh`), lanza la clase:
   `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.ProveedorLlaveroTest`
   Tienen que pasar las de antes y las cinco nuevas. Si falla alguna, el informe está en `app/build/reports/androidTests/connected/debug/`.
2. Si pasa, la batería entera (`./gradlew :app:connectedDebugAndroidTest`, unos 30 s): `PuntaAPuntaTest` falla sin su servidor (`herramientas/punta_a_punta.py`), y eso es lo esperado.
3. La prueba a mano con KeePassDX 4.5.6, contra el servidor de `herramientas/punta_a_punta.py` y con una base de verdad en su `keychain/` (la sintética no se abre; se hace con `keepassxc-cli`):
   a. «Abrir llavero» y desbloquea la base en KeePassDX.
   b. Cambia la base en el remoto por detrás, como el «PC» (`keepassxc-cli add` sobre la del servidor; va con `--dir-cache-time 0s`, así que se ve en seguida).
   c. En prdrive, «Sincronizar».
   d. Vuelve a KeePassDX: sale «modified outside the app». Cierra con «OK», cambia una entrada y guarda.
   e. Tiene que pasar todo esto: la base de `.keychain/` sigue siendo la del PC, aparece `<base>.conflicto-dispositivo<fecha>.kdbx` con lo guardado, sale la notificación «Tu guardado de … está aparte», la fila la cuenta como copia y, con red, se sube.
   f. Guarda otra vez sin recargar: va a la misma copia, sin notificación nueva.
   g. «Recargar» en KeePassDX y guarda: ahora va encima de la base.
   Mira también (con `adb shell run-as prdrive.app cat files/volumen/.prdrive/state/llavero-lecturas.json`) que la clave de la app es el paquete de KeePassDX (`com.kunzisoft.keepass.free` o `.pro`). Es lo único de la D que no se ha visto nunca en un dispositivo.
4. Apunta lo que salga en `PLAN.md` §1 (el punto «KeePassDX ve el cambio en disco…») y haz commit en la rama. Después, pregúntale a la persona si abre el PR y si sube `VERSION` (subir `VERSION` a main es publicar una release: «Versiones y releases» en `AGENTS.md`).

### Blockers/Open Questions

- [ ] Blocker: hace falta un emulador con aceleración (KVM o la de tu sistema) - Needs: la máquina de la persona o un runner con KVM; en un contenedor sin `/dev/kvm` no arranca
- [ ] Question: ¿`callingPackage` es el de KeePassDX al leer y al guardar? - Suggested: el paso 3 lo contesta; si sale `null` o el de DocumentsUI, la D no se activa (falla abierto) y hay que replantear la clave

### Deferred Items

- Keepass2Android: no se ha mirado cómo lee y guarda (deferred because: no estaba en el emulador; `PLAN.md` §1 ya lo apunta)
- El PR y `VERSION` (deferred because: los decide la persona después de las pruebas)

## Context for Resuming Agent

### Important Context

- **El objetivo es probar, no reescribir.** La implementación está hecha y revisada. Si una prueba falla, busca la causa antes de cambiar el código, y no toques las pruebas para que pasen: `AGENTS.md` prohíbe escribir valores esperados a mano.
- **El caso que se arregla:** KeePassDX tiene la versión A en memoria, una pasada pone la B en el disco, y KeePassDX guarda A con sus cambios sin volver a leer. Antes eso pisaba B, que quedaba solo en `.prversions/`. Ahora va a una copia de conflicto.
- **Qué cuenta como «tiene la versión»:** la última que la app leyó con `"r"` o la que puso ella misma. Con una copia aparte, sus guardados viejos siguientes van a esa copia mientras la copia siga siendo lo último que escribió (por su huella); si alguien la cambia, va a otra nueva.
- **Las pruebas simulan la pasada** escribiendo la base directamente en el disco (`base.writeBytes(...)`), sin pasar por el proveedor. Eso es lo que hace rclone.

### Assumptions Made

- Que `callingPackage` identifica a la app de KeePass en `openDocument`, tanto al leer como al guardar (no verificado en un dispositivo).
- Que KeePassDX sigue leyendo la base con `"r"` (visto en la prueba del 23/10/2026, `PLAN.md` §1).

### Potential Gotchas

- `esperarAlCierre()` vuelve antes de que acabe lo que viene después del cierre: espera a lo que compruebes (`esperar { copias().size == 1 }`, `esperar { encolados().isNotEmpty() }`), como hacen las pruebas nuevas.
- `connectedDebugAndroidTest` instala en **todos** los dispositivos conectados: con un teléfono enchufado, pon `ANDROID_SERIAL`.
- A veces el emulador no encuentra la instrumentación recién instalada y la pasada sale sin pruebas (el build la da por fallida): se vuelve a lanzar.
- Para compilar en Linux sin locale, `:engine:test` falla al escribir el informe HTML (nombres con acentos): `LC_ALL=C.UTF-8`.
- Maven Central puede devolver 429 de vez en cuando: se reintenta.
- La copia de un guardado apartado no lleva el número del primer libre a propósito (ver «Decisions Made»): no la «arregles» para que salga `1`.

## Environment State

### Tools/Services Used

- Android SDK: `platforms;android-37.0`, `build-tools;37.0.0` (AGP pidió además la 36.0.0 y la instaló solo), `platform-tools` y `ndk;28.2.13676358` (r28c)
- Go: el `toolchain` de `rclone/go/go.mod` (go1.27.3, se baja solo con `GOTOOLCHAIN=auto`), y gomobile y gobind de la versión que fija ese `go.mod`
- JDK 21

### Active Processes

- Ninguno: el contenedor de esta sesión se borra al cerrarla. El SDK y el `.aar` que se construyeron allí no llegan a tu máquina.

### Environment Variables

- `ANDROID_HOME`, `ANDROID_NDK_HOME` (opcional), `JAVA_HOME`, `ANDROID_SERIAL` (para elegir el emulador), `LC_ALL`, `GOTOOLCHAIN`

## Related Resources

- `AGENTS.md`: «El llavero», «Órdenes», «Ojo con Android»
- `PLAN.md` §1: las pruebas con KeePassDX del 23/10/2026
- `herramientas/punta_a_punta.py`: el servidor SFTP con el llavero
- KeePassDX 4.5.6: `DatabaseTaskNotificationService.checkDatabaseInfo`, `SaveDatabaseRunnable`, `MergeDatabaseRunnable` y `UriHelper.getUriOutputStream` (https://github.com/Kunzisoft/KeePassDX/tree/4.5.6)
- La skill con la que se hizo este handoff: `session-handoff` de https://github.com/softaworks/agent-toolkit

---

**Security Reminder**: Before finalizing, run `validate_handoff.py` to check for accidental secret exposure.
