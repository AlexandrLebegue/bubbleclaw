package dev.bubbleclaw

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local executor + JSON schema for the optional phone-control tools. */
object ControlTools {

    data class Outcome(val text: String, val imageDataUrl: String? = null)

    fun schema(): JSONArray {
        val tools = JSONArray()
        tools.put(
            function(
                "tap",
                "Touche un point de l'écran. Coordonnées en pixels de la capture fournie.",
                JSONObject()
                    .put("x", number("Abscisse en pixels de la capture."))
                    .put("y", number("Ordonnée en pixels de la capture.")),
                arrayOf("x", "y")
            )
        )
        tools.put(
            function(
                "swipe",
                "Fait glisser un doigt d'un point à un autre.",
                JSONObject()
                    .put("x1", number("Abscisse de départ."))
                    .put("y1", number("Ordonnée de départ."))
                    .put("x2", number("Abscisse d'arrivée."))
                    .put("y2", number("Ordonnée d'arrivée."))
                    .put("durationMs", number("Durée du geste en millisecondes, 300 par défaut.")),
                arrayOf("x1", "y1", "x2", "y2")
            )
        )
        tools.put(
            function(
                "scroll",
                "Fait défiler l'écran dans une direction.",
                JSONObject()
                    .put("direction", enum("up", "down", "left", "right"))
                    .put("amount", number("Amplitude du défilement en pixels de la capture.")),
                arrayOf("direction")
            )
        )
        tools.put(
            function(
                "type_text",
                "Saisit du texte dans le champ actuellement sélectionné.",
                JSONObject().put("text", string("Texte à saisir.")),
                arrayOf("text")
            )
        )
        tools.put(
            function(
                "press_key",
                "Envoie une touche système : retour, accueil, applications récentes ou entrée.",
                JSONObject().put("key", enum("back", "home", "recents", "enter")),
                arrayOf("key")
            )
        )
        tools.put(
            function(
                "open_app",
                "Ouvre une application par son nom ou son identifiant de package.",
                JSONObject().put("query", string("Nom ou package de l'application.")),
                arrayOf("query")
            )
        )
        tools.put(
            function(
                "screenshot",
                "Prend une nouvelle capture de l'écran pour vérifier le résultat d'une action.",
                JSONObject(),
                arrayOf()
            )
        )
        return tools
    }

    fun execute(call: ToolCall, context: ScreenContextInfo?): Outcome {
        val service = ScreenReaderService.instance
            ?: return Outcome("Service d'accessibilité non connecté. Active-le dans les réglages.")
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return try {
            when (call.name) {
                "tap" -> {
                    val point = mapPoint(args.optDouble("x", 0.0), args.optDouble("y", 0.0), context)
                    Outcome(service.performTap(point.first, point.second))
                }
                "swipe" -> {
                    val start = mapPoint(args.optDouble("x1", 0.0), args.optDouble("y1", 0.0), context)
                    val end = mapPoint(args.optDouble("x2", 0.0), args.optDouble("y2", 0.0), context)
                    Outcome(
                        service.performSwipe(
                            start.first, start.second, end.first, end.second,
                            args.optInt("durationMs", 300)
                        )
                    )
                }
                "scroll" -> {
                    val direction = args.optString("direction", "down")
                    val amount = (args.optDouble("amount", 400.0) * scaleY(context)).toInt()
                    Outcome(service.performScroll(direction, amount))
                }
                "type_text" -> Outcome(service.typeText(args.optString("text", "")))
                "press_key" -> {
                    when (args.optString("key", "back")) {
                        "enter" -> Outcome(service.performEnter())
                        else -> Outcome(service.performGlobal(args.optString("key", "back")))
                    }
                }
                "open_app" -> Outcome(service.openApp(args.optString("query", "")))
                "screenshot" -> captureFresh(service)
                else -> Outcome("Outil inconnu : ${call.name}")
            }
        } catch (e: Exception) {
            Outcome("Action impossible : ${e.message ?: "erreur inconnue"}")
        }
    }

    private fun captureFresh(service: ScreenReaderService): Outcome {
        val latch = CountDownLatch(1)
        var outcome = Outcome("Capture impossible.")
        service.capture { capture ->
            if (capture == null) {
                outcome = Outcome("La capture d'écran a échoué. Réessaie ou demande à l'utilisateur.")
            } else {
                val data = Base64.encodeToString(capture.jpeg, Base64.NO_WRAP)
                outcome = Outcome(
                    "Capture actualisée : ${capture.bitmap.width} × ${capture.bitmap.height} pixels. " +
                        "Utilise ces dimensions pour les prochaines coordonnées.",
                    "data:image/jpeg;base64,$data"
                )
            }
            latch.countDown()
        }
        latch.await(6, TimeUnit.SECONDS)
        return outcome
    }

    private fun mapPoint(x: Double, y: Double, context: ScreenContextInfo?): Pair<Float, Float> {
        val metrics = ScreenReaderService.instance?.resources?.displayMetrics
        val screenW = metrics?.widthPixels?.toFloat() ?: return Pair(x.toFloat(), y.toFloat())
        val screenH = metrics?.heightPixels?.toFloat() ?: return Pair(x.toFloat(), y.toFloat())
        val sx = if (context != null && context.width > 0) screenW / context.width else 1f
        val sy = if (context != null && context.height > 0) screenH / context.height else 1f
        return Pair((x * sx).toFloat(), (y * sy).toFloat())
    }

    private fun scaleY(context: ScreenContextInfo?): Double {
        val metrics = ScreenReaderService.instance?.resources?.displayMetrics ?: return 1.0
        return if (context != null && context.height > 0) {
            metrics.heightPixels.toDouble() / context.height
        } else 1.0
    }

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: Array<String>
    ): JSONObject {
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required.toList()))
        return JSONObject()
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", description).put("parameters", parameters))
    }

    private fun number(description: String) = JSONObject().put("type", "number").put("description", description)

    private fun string(description: String) = JSONObject().put("type", "string").put("description", description)

    private fun enum(vararg values: String) = JSONObject()
        .put("type", "string")
        .put("enum", JSONArray(values.toList()))
}
