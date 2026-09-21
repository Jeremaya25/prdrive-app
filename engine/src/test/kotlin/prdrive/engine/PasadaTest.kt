package prdrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PasadaTest.kt — Montar la llamada, y no perder nada por el camino.
 *
 * Aquí se cruzan las dos fuentes de verdad. Lo que sale de prdrive —las agujas
 * de `KNOWN_ERRORS`, los flags que el motor se reserva, las constantes de una
 * pasada— se compara contra `vectores.json`. Lo que depende de rclone —los
 * nombres y los tipos de los parámetros, y si un flag llega o se descarta— se
 * comprueba en `SesionesTest` contra lo que rclone aceptó de verdad.
 *
 * Y en medio están los tests que guardan las trampas: **las tres formas de
 * perder un flag sin que nada falle**. Son las que importan, porque el síntoma
 * de equivocarse aquí no es un error: es un «Simular» que sincroniza, o un
 * `--max-delete` que no limita nada.
 */
class PasadaTest {

    private val ejecucion = Vectores.seccion("ejecucion")
    private val config = parseConfig(Vectores.objeto(Vectores.seccion("resueltas"), "config"))

    private fun pareja(nombre: String): Pareja = config.pairs.first { it.name == nombre }

    private val op = Opciones(workdir = "/datos/volumen/.prdrive/state/notas")

    // -----------------------------------------------------------------------
    // Lo que viene de prdrive
    // -----------------------------------------------------------------------

    @Test
    fun `las agujas de KNOWN_ERRORS son las de prdrive, en su orden`() {
        // Las agujas son las cadenas que escribe rclone, así que una que se
        // quede vieja hace desaparecer el diagnóstico sin que nada falle. El
        // orden también es del original: las dos últimas de prdrive son
        // errores de arranque, y van al final porque si un log trae además una
        // de las de arriba, esa es la que ocurrió de verdad.
        //
        // Las explicaciones NO se comparan letra a letra: la app habla de «la
        // carpeta del volumen» donde el PC habla de «la ruta local».
        val esperadas = Vectores.objetos(ejecucion, "known_errors").map { Vectores.texto(it, "aguja") }
        assertEquals(esperadas, Pasada.KNOWN_ERRORS_PRDRIVE.map { it.first })
        assertTrue(
            Pasada.KNOWN_ERRORS.take(esperadas.size).map { it.first } == esperadas,
            "las de prdrive tienen que ir primero: las de la API rc son de arranque",
        )
        for ((_, explicacion) in Pasada.KNOWN_ERRORS) {
            assertTrue(explicacion.isNotBlank(), "una aguja sin explicación no sirve de nada")
        }
    }

    @Test
    fun `los flags que pone la app son los que prdrive se reserva`() {
        // Mismo juego de claves que `ui/flags_editor.RESERVED`. Los motivos se
        // reescriben («lo pone sync.py» no significa nada aquí), las claves no.
        val esperados = Vectores.objeto(ejecucion, "reservados").keys
        assertEquals(esperados, Pasada.DEL_MOTOR.keys)
        for ((flag, motivo) in Pasada.DEL_MOTOR) {
            assertTrue(motivo.isNotBlank(), "$flag sin motivo")
        }
    }

    @Test
    fun `las constantes de una pasada son las de sync punto py`() {
        assertEquals((ejecucion["saltada"] as Long).toInt(), Pasada.SALTADA)
        assertEquals((ejecucion["lineas_de_cola"] as Long).toInt(), Pasada.LINEAS_DE_COLA)
        assertEquals((ejecucion["conflictos_mostrados"] as Long).toInt(), Pasada.CONFLICTOS_MOSTRADOS)
    }

    // -----------------------------------------------------------------------
    // Las tres formas de perder un flag
    // -----------------------------------------------------------------------

    @Test
    fun `ningún flag viaja dentro de _config ni de _filter`() {
        // LA trampa. `{"_config":{"dry_run":true}}` no pone ningún dry-run:
        // dentro de `_config` el nombre que casa es el del CAMPO de Go
        // (`DryRun`), porque `rc.Reshape` es un json.Unmarshal y `ConfigInfo`
        // no lleva etiquetas `json`. Medido en rclone/spike: con esa forma se
        // copiaron los 2 ficheros; con el flag suelto, 0.
        //
        // O sea que si esto falla, el «Simular» de la app sincroniza de verdad.
        for (p in config.pairs) {
            val params = Pasada.traducir(p, op).params
            assertTrue("_config" !in params, "[${p.name}] hay un _config en la llamada")
            assertTrue("_filter" !in params, "[${p.name}] hay un _filter en la llamada")
        }
    }

    @Test
    fun `el dry-run de un modo espejo va suelto y con el nombre de la etiqueta`() {
        val params = Pasada.traducir(pareja("espejo"), Opciones(dryRun = true)).params
        assertEquals(true, params["dry_run"])
        assertTrue("dryRun" !in params, "dryRun en camello es el parámetro de bisync, no de sync")
    }

    @Test
    fun `el dry-run de bisync va en su propio parámetro`() {
        // En bisync no vale el suelto: además de `ci.DryRun`, el parámetro
        // `dryRun` pone `opt.DryRun`, que es lo que mira bisync para no
        // escribir sus listados (cmd/bisync/rc.go).
        val params = Pasada.traducir(pareja("notas"), op.copy(dryRun = true)).params
        assertEquals(true, params["dryRun"])
        assertTrue("dry_run" !in params)
    }

    @Test
    fun `los enumerados de bisync viajan como cadena aunque el TOML traiga un booleano`() {
        // `setEnum` (cmd/bisync/rc.go) lee con `in.GetString` y trata «no es
        // una cadena» igual que «no está»: un `check-sync = false` escrito
        // como booleano se ignora sin decir nada. Convertirlo a "false" es lo
        // que hace rclone con `--check-sync=false` en la línea de órdenes.
        val raw = Vectores.objeto(Vectores.seccion("resueltas"), "config").toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val parejas = (raw["pair"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }
        parejas[0]["flags"] = mapOf("check-sync" to false, "max-lock" to "5m")
        raw["pair"] = parejas
        val notas = parseConfig(raw).pairs.first()

        val params = Pasada.traducir(notas, op).params
        assertEquals("false", params["checkSync"], "un booleano en checkSync se ignoraría")
        assertEquals("5m", params["maxLock"])
    }

    @Test
    fun `un número donde va un booleano se rechaza en vez de adivinar`() {
        assertFailsWith<ConfigError> {
            Pasada.cuadrar("resilient", TipoRpc.BOOL, 1L)
        }
        assertFailsWith<ConfigError> {
            Pasada.cuadrar("max-delete", TipoRpc.ENTERO, "veinticinco")
        }
        // Y al revés sí, porque es lo que hace rclone en la línea de órdenes.
        assertEquals("true", Pasada.cuadrar("check-sync", TipoRpc.CADENA, true))
        assertEquals("25", Pasada.cuadrar("compare", TipoRpc.CADENA, 25L))
        assertEquals(25L, Pasada.cuadrar("max-delete", TipoRpc.ENTERO, 25))
    }

    // -----------------------------------------------------------------------
    // La traducción, pareja por pareja
    // -----------------------------------------------------------------------

    @Test
    fun `una pareja de bisync lleva sus extremos y los flags de su modo`() {
        val notas = pareja("notas")
        val params = Pasada.traducir(notas, op).params
        assertEquals("sync/bisync", Pasada.metodo(notas))
        assertEquals(notas.source, params["path1"])
        assertEquals(notas.dest, params["path2"])
        // Los de MODES["bisync"], ya en camello y con su tipo.
        assertEquals("newer", params["conflictResolve"])
        assertEquals("conflicto-dispositivo,conflicto-remoto", params["conflictSuffix"])
        assertEquals(25L, params["maxDelete"])
        assertEquals(true, params["resilient"])
        assertEquals(true, params["recover"])
        assertEquals("2m", params["maxLock"])
        assertEquals(true, params["createEmptySrcDirs"])
        // Y los de [defaults.flags], que no son parámetros de bisync: sueltos.
        assertEquals(4L, params["transfers"])
        assertEquals(op.workdir, params["workdir"])
    }

    @Test
    fun `una pareja de bisync sin workdir no se ejecuta`() {
        // Sin él rclone usaría su carpeta por defecto y el baseline se
        // perdería en cada pasada, que es el fallo más caro que puede tener
        // esto: cada pasada parecería la primera.
        val e = assertFailsWith<ConfigError> { Pasada.traducir(pareja("notas"), Opciones()) }
        assertTrue("workdir" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `un modo espejo lleva srcFs, dstFs y sus patrones sueltos`() {
        val espejo = pareja("espejo")
        assertEquals("sync/sync", Pasada.metodo(espejo))
        val params = Pasada.traducir(espejo, Opciones()).params
        assertEquals(espejo.source, params["srcFs"])
        assertEquals(espejo.dest, params["dstFs"])
        // `use_filters_file = false`, así que los patrones van como filtro
        // global de rclone. Sueltos, con el nombre de la etiqueta.
        assertEquals(espejo.excludes, params["exclude"])
        assertTrue("filtersFile" !in params)
        // Y el max-delete de los modos espejo, que aquí SÍ es una cuenta de
        // ficheros y no un porcentaje: por eso va suelto y no como maxDelete.
        assertEquals(50L, params["max_delete"])
        assertTrue("maxDelete" !in params)
    }

    @Test
    fun `una pareja que sube va en el sentido del modo`() {
        val subida = pareja("subida")
        assertEquals("sync/copy", Pasada.metodo(subida))
        val params = Pasada.traducir(subida, Opciones()).params
        assertEquals(subida.localEndpoint, params["srcFs"])
        assertEquals(subida.remoteEndpoint, params["dstFs"])
    }

    @Test
    fun `los includes de una pareja que no es bisync viajan como lista`() {
        // `filter.Options` lleva `RulesOpt` incrustado, así que `include` y
        // `exclude` son opciones sueltas de rclone (fs/filter/rules.go).
        val raw = Vectores.objeto(Vectores.seccion("resueltas"), "config").toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val parejas = (raw["pair"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }
        val espejo = parejas.first { it["name"] == "espejo" }
        espejo["include"] = listOf("*.jpg", "*.raw")
        raw["pair"] = parejas
        val p = parseConfig(raw).pairs.first { it.name == "espejo" }
        val params = Pasada.traducir(p, Opciones()).params
        assertEquals(listOf("*.jpg", "*.raw"), params["include"])
        assertEquals(listOf("*.tmp"), params["exclude"])
    }

    @Test
    fun `un flag que pone la app no se admite en el config`() {
        // Igual que `flags_editor` en el escritorio: se rechaza al preparar la
        // pasada y no a mitad de faena. Un segundo `--workdir` apuntaría
        // bisync a otro baseline.
        val raw = Vectores.objeto(Vectores.seccion("resueltas"), "config").toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val parejas = (raw["pair"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }
        parejas[0]["flags"] = mapOf("workdir" to "/otro/sitio")
        raw["pair"] = parejas
        val e = assertFailsWith<ConfigError> {
            Pasada.traducir(parseConfig(raw).pairs.first(), op)
        }
        assertTrue("workdir" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `los flags que no pueden viajar se dicen, no se callan`() {
        // `stats` y `stats-one-line` están en las BASE_FLAGS de prdrive y aquí
        // no tienen a dónde ir: el progreso sale de core/stats. Que se queden
        // fuera es correcto; que se queden fuera en silencio, no — el catálogo
        // es el mismo fichero para el PC y para el móvil, así que la pantalla
        // tiene que poder decir qué no se aplica.
        val t = Pasada.traducir(pareja("notas"), op)
        assertTrue("stats" in t.ignorados, "los ignorados: ${t.ignorados.keys}")
        assertTrue("stats-one-line" in t.ignorados)
        assertTrue("stats" !in t.params && "stats_one_line" !in t.params)
        for ((flag, motivo) in t.ignorados) {
            assertTrue(motivo.isNotBlank(), "$flag se ignora sin decir por qué")
        }
    }

    @Test
    fun `un flag puesto a false se cae, salvo si es parámetro del método`() {
        // `false` y `null` son «quitar el flag», igual que en flagsToArgs. Pero
        // el valor por defecto de un parámetro de bisync puede no ser false,
        // así que ahí sí se manda.
        val raw = Vectores.objeto(Vectores.seccion("resueltas"), "config").toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val parejas = (raw["pair"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }
        parejas[0]["flags"] = mapOf("checksum" to false, "resilient" to false)
        raw["pair"] = parejas
        val params = Pasada.traducir(parseConfig(raw).pairs.first(), op).params
        assertTrue("checksum" !in params, "un suelto en false no se manda")
        assertEquals(false, params["resilient"], "un parámetro de bisync en false sí")
    }

    @Test
    fun `el nombre de un parámetro es el flag en camello y el de un suelto con guión bajo`() {
        assertEquals("maxLock", Pasada.aCamello("max-lock"))
        assertEquals("createEmptySrcDirs", Pasada.aCamello("create-empty-src-dirs"))
        assertEquals("backupDir1", Pasada.aCamello("backup-dir1"))
        assertEquals("resync", Pasada.aCamello("resync"))
        assertEquals("statsOneLine", Pasada.aCamello("stats_one_line"))
        assertEquals("max_delete", Pasada.aSnake("max-delete"))
        assertEquals("checksum", Pasada.aSnake("checksum"))
    }

    // -----------------------------------------------------------------------
    // La llamada, y lo que contesta
    // -----------------------------------------------------------------------

    @Test
    fun `toda pasada lleva _async, y por una razón`() {
        // `librclone.RPC` descarta el `out` cuando la llamada devuelve error
        // (writeError, librclone/librclone.go), así que sin `_async` una
        // pasada fallida pierde su salida justo cuando hace falta.
        for (p in config.pairs) {
            val peticion = Pasada.peticion(p, op.copy(grupo = Pasada.grupoDe(p)))
            assertEquals(true, peticion.params["_async"], "[${p.name}] sin _async")
            assertEquals("prdrive/${p.name}", peticion.params["_group"])
            assertTrue(peticion.json.startsWith("""{"_async":true,"_group":"""))
        }
    }

    @Test
    fun `el nivel de log del verbose de prdrive es INFO`() {
        // No es un flag que se pueda mandar en la llamada: es global, y hay que
        // ponerlo con RcloneLogNivel antes. INFO es lo que hace falta para que
        // el log traiga las líneas que KNOWN_ERRORS reconoce.
        assertEquals("INFO", Pasada.nivelDeLog(BASE_FLAGS))
        assertEquals("INFO", Pasada.nivelDeLog(pareja("notas").flags))
        assertEquals("DEBUG", Pasada.nivelDeLog(mapOf("log-level" to "debug")))
        assertEquals("ERROR", Pasada.nivelDeLog(mapOf("quiet" to true)))
        assertEquals("NOTICE", Pasada.nivelDeLog(emptyMap()))
    }

    @Test
    fun `el jobid sale de la respuesta, y su falta se explica`() {
        assertEquals(7L, Pasada.jobid(RespuestaRpc("""{"jobid":7}""", 200)))
        // Sin jobid es que no se lanzó con _async, y eso hay que decirlo así:
        // el síntoma sería perder el log de las pasadas que fallan.
        val e = assertFailsWith<RcloneError> { Pasada.jobid(RespuestaRpc("""{"ok":true}""", 200)) }
        assertTrue("_async" in e.message.orEmpty(), e.message.orEmpty())
        // Y un error de rclone llega con su mensaje, no con el JSON crudo.
        val fallo = assertFailsWith<RcloneError> {
            Pasada.jobid(RespuestaRpc("""{"error":"unknown remote nas"}""", 500))
        }
        assertEquals("unknown remote nas", fallo.message)
        assertEquals(500, fallo.estado)
    }

    @Test
    fun `job-status trae el error y la salida a la vez`() {
        // Es la razón de ser de `_async`: `job.finish()` asigna `job.Output`
        // ANTES de mirar el error (fs/rc/jobs/job.go), así que en una pasada
        // fallida están las dos cosas.
        val estado = Pasada.estadoJob(
            RespuestaRpc(
                """{"finished":true,"success":false,"error":"bisync aborted",""" +
                    """"output":{"session":"disp_x..nas_y","output":"ERROR : algo"}}""",
                200,
            ),
        )
        assertTrue(estado.acabado)
        assertTrue(!estado.exito)
        assertEquals("bisync aborted", estado.error)
        assertEquals("disp_x..nas_y", estado.salida["session"])
    }

    @Test
    fun `un job sin acabar se lee como sin acabar`() {
        val estado = Pasada.estadoJob(RespuestaRpc("""{"finished":false,"success":false}""", 200))
        assertTrue(!estado.acabado)
        assertEquals("", estado.error)
        assertEquals(emptyMap(), estado.salida)
    }

    // -----------------------------------------------------------------------
    // Traducir el fallo
    // -----------------------------------------------------------------------

    @Test
    fun `el diagnóstico es el de la primera aguja, y no se adivina`() {
        // Un diagnóstico falso es peor que ninguno: en el escritorio pasaba
        // cuando rclone volcaba su ayuda de 12 KB detrás del error y dentro
        // salía «--max-delete».
        assertEquals(
            Pasada.KNOWN_ERRORS_PRDRIVE[0].second,
            Pasada.explicarFallo("ERROR : Bisync critical error: ${Bisync.MISSING_LISTINGS}"),
        )
        assertNull(Pasada.explicarFallo("ERROR : algo que nadie ha visto nunca"))
        assertNull(Pasada.explicarFallo(""))
    }

    @Test
    fun `si el log trae un fallo de sincronización y otro de arranque, gana el primero`() {
        // El de arranque va al final de la lista a propósito.
        val log = "ERROR : ${Bisync.MISSING_LISTINGS}\nError: unknown flag: --inventado\n"
        assertEquals(Pasada.KNOWN_ERRORS_PRDRIVE[0].second, Pasada.explicarFallo(log))
    }

    @Test
    fun `la cola del log va sin estadísticas`() {
        // Con una estadística cada pocos segundos, una pasada que se queda
        // pensando antes de fallar llenaba estas líneas de números y el error
        // se quedaba fuera.
        val log = buildString {
            repeat(30) { append("2026/09/21 07:56:16 INFO  : linea $it\n") }
            append("2026/09/21 07:56:16 INFO  : Transferred: 1.086 MiB / 2.500 MiB, 43%, 512 KiB/s, ETA 3s\n")
            append("2026/09/21 07:56:16 ERROR : lo que hay que leer\n")
        }
        val cola = Pasada.colaDelLog(log)
        assertEquals(Pasada.LINEAS_DE_COLA, cola.size)
        assertTrue(cola.last().endsWith("lo que hay que leer"))
        assertTrue(cola.none { "ETA" in it }, "las estadísticas no van en la cola")
    }
}
