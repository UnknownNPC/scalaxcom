import io.circe.{Json, JsonObject}
import io.circe.parser.parse

// Turns a string enum back into a plain string when two of its values would
// get the same Scala name. Runs before openapi-generator-cli sees the spec.
//
// scala-sttp names an enum value by camel-casing it with punctuation dropped,
// so `chat.conversation.join` and `chat.conversation_join` (both in the
// activity subscription `event_type`) become the same `ChatConversationJoin`
// — two vals with one name, which does not compile. Such an enum is typed as
// a `String` instead; its documented values are still in the description.
object EnumNameClashSpecFix {

  def fix(rawSpecJson: String): String = {
    val json = parse(rawSpecJson).fold(
      err => sys.error(s"EnumNameClashSpecFix: failed to parse spec JSON: ${err.getMessage}"),
      identity,
    )
    walk(json).spaces2
  }

  private def scalaName(value: String): String =
    value.split("[^A-Za-z0-9]+").filter(_.nonEmpty).map(_.capitalize).mkString.toLowerCase

  private def hasClash(values: Vector[Json]): Boolean = {
    val names = values.flatMap(_.asString).map(scalaName)
    names.distinct.size != names.size
  }

  private def walk(j: Json): Json =
    j.fold(
      jsonNull = j,
      jsonBoolean = _ => j,
      jsonNumber = _ => j,
      jsonString = _ => j,
      jsonArray = items => Json.fromValues(items.map(walk)),
      jsonObject = obj => {
        val fixed = obj("enum").flatMap(_.asArray) match {
          case Some(values) if hasClash(values) => obj.remove("enum")
          case _                                => obj
        }
        Json.fromJsonObject(fixed.mapValues(walk))
      },
    )
}
