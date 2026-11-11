import io.circe.{Json, JsonObject}
import io.circe.parser.parse

// Drops `anyOf`/`oneOf` lists made only of `{required: [...]}` members, keeping
// the schema around them. Runs before openapi-generator-cli sees the spec.
//
// The X spec uses that shape for "at least one of these properties must be
// set" (e.g. `UpdateChatSettingsRequest`). It is a validation rule, not a
// type: every property is already declared on the schema itself. But
// openapi-generator treats the schema as composed, skips its inline enums,
// and emits fields typed `<Model>Enums.X` with no `<Model>Enums` object to
// back them, so nothing compiles. The rule is left to the server.
object RequiredOnlyCompositionSpecFix {

  private val Keywords = Seq("anyOf", "oneOf")

  def fix(rawSpecJson: String): String = {
    val json = parse(rawSpecJson).fold(
      err => sys.error(s"RequiredOnlyCompositionSpecFix: failed to parse spec JSON: ${err.getMessage}"),
      identity,
    )
    walk(json).spaces2
  }

  private def isRequiredOnly(member: Json): Boolean =
    member.asObject.exists(o => o.size == 1 && o.contains("required"))

  private def walk(j: Json): Json =
    j.fold(
      jsonNull = j,
      jsonBoolean = _ => j,
      jsonNumber = _ => j,
      jsonString = _ => j,
      jsonArray = items => Json.fromValues(items.map(walk)),
      jsonObject = obj => {
        val fixed = Keywords.foldLeft(obj)((o, keyword) =>
          o(keyword).flatMap(_.asArray) match {
            case Some(members) if members.nonEmpty && members.forall(isRequiredOnly) && o.contains("properties") =>
              o.remove(keyword)
            case _ => o
          },
        )
        Json.fromJsonObject(fixed.mapValues(walk))
      },
    )
}
