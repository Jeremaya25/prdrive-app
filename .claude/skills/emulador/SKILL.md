---
name: emulador
description: Usar cuando hay que pasar los tests instrumentados de app/ (androidTest, connectedDebugAndroidTest) o probar algo en un emulador o un teléfono, cuando falta rclone/build/rclone.aar, o cuando un test de Android falla, sale «No se ha ejecutado ningún test» o el informe dice «Could not load test results».
---

# Tests de Android en un emulador

`connectedDebugAndroidTest` instala y ejecuta en **todos** los dispositivos
conectados, teléfonos incluidos. Los tests **borran el volumen de la app**
(`Banco.volumen` hace `deleteRecursively()` de `filesDir/volumen`) y al acabar
desinstala `prdrive.app` y `prdrive.app.test`, con todos sus datos. Un emulador
con un emparejamiento hecho a mano lo pierde, y dos pasadas a la vez en el mismo
emulador se pisan. Siempre con `ANDROID_SERIAL`, y nunca contra un dispositivo
que no sea tuyo.

## Antes

1. **El SDK.** `:app` solo existe si hay `ANDROID_HOME` (o `sdk.dir` en
   `local.properties`); sin él, «project 'app' not found».

   ```bash
   export ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
   adb="$ANDROID_HOME/platform-tools/adb"; emu="$ANDROID_HOME/emulator/emulator"
   ```

2. **El `.aar` de rclone.** No se versiona y un worktree nuevo no lo tiene
   («Falta …/rclone/build/rclone.aar»). Se construye con `bash rclone/construir.sh`:
   Go del `toolchain` de `rclone/go/go.mod` en el PATH (en Windows suele estar en
   `$USERPROFILE/sdk/go<versión>/bin`), `gomobile` y `gobind` en
   `$(go env GOPATH)/bin` y un NDK. Tarda unos minutos: en segundo plano. Hay que
   reconstruirlo si cambian `rclone/go` o `construir.sh`.

3. **El dispositivo.** `"$adb" devices` y `"$emu" -list-avds`. Un emulador que
   ya corre no es tuyo si tiene cualquiera de estas cosas:
   - `prdrive.app.test` instalado (`"$adb" -s <serie> shell pm list packages prdrive`):
     hay una pasada en marcha de otra sesión, porque al acabar se desinstala;
   - un volumen (`"$adb" -s <serie> shell run-as prdrive.app ls files/volumen/.prdrive`);
   - la app abierta (`"$adb" -s <serie> shell pidof prdrive.app`).

   Entonces arranca otro AVD de solo lectura (no guarda nada al cerrarse) y usa
   su serie. El arranque y la espera van en segundo plano:

   ```bash
   "$emu" -avd <avd> -read-only -no-window -no-audio -no-boot-anim -no-snapshot
   # en otra orden, también en segundo plano:
   "$adb" -s emulator-5556 wait-for-device
   until [ "$("$adb" -s emulator-5556 shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 5; done
   ```

   La serie es `emulator-<puerto>`: la primera par libre desde 5554.

## Ejecutar

```bash
export ANDROID_SERIAL=emulator-5554
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.RcloneSpikeTest
```

Sin el `-P…class`, la batería entera (unos 30 s con el APK compilado; la primera
compilación en un worktree, unos minutos). Un método:
`class=prdrive.app.PrincipalTest#nombre_del_test`. La salida dice «Running tests
on devices: …»: tiene que salir solo el tuyo.

## Si falla

| Síntoma | Qué es |
|---|---|
| «No se ha ejecutado ningún test» | El emulador no encontró la instrumentación recién instalada. Se relanza igual; si vuelve, el filtro de clase está mal escrito. |
| Un test rojo | `app/build/reports/androidTests/connected/debug/index.html`; los XML, en `app/build/outputs/androidTest-results/connected/`. |
| «Could not load test results» | Un carácter de control en el XML (los colores del log de bisync): el test tiene que usar la regla `Banco.Legible`. |
| `PuntaAPuntaTest` no corre | Se deja fuera sin carga del QR: `python herramientas/punta_a_punta.py <prdrive> <rclone> <dir>` monta el servidor y dice cómo lanzarla. |
| Una conexión a `10.0.2.2` se cuelga | Desde API 37 la red local pide `ACCESS_LOCAL_NETWORK`; sin él no falla, espera al timeout. |
| «Unable to establish loopback connection» | Falta el `JAVA_TOOL_OPTIONS` de AGENTS.md, «Órdenes». |

## Al acabar

Si arrancaste un emulador, apágalo: `"$adb" -s emulator-5556 emu kill`.
